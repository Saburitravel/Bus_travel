package com.saburi.travel.service;

import com.saburi.travel.model.*;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final Pattern PHONE = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private final Repos.UserRepo users;
    private final Repos.SessionRepo sessions;
    private final Time time;
    private final String adminEmail, adminPassword, adminPhone;
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();
    private final Map<String, long[]> resetFailures = new ConcurrentHashMap<>();

    public AuthService(Repos.UserRepo users, Repos.SessionRepo sessions, Time time,
                       @Value("${app.admin.email}") String adminEmail,
                       @Value("${app.admin.password}") String adminPassword,
                       @Value("${app.admin.phone}") String adminPhone) {
        this.users=users; this.sessions=sessions; this.time=time;
        this.adminEmail=adminEmail; this.adminPassword=adminPassword; this.adminPhone=normalizePhone(adminPhone);
    }
    public static String normalizePhone(String p){ String d=p==null?"":p.replaceAll("\\D",""); return d.length()>10?d.substring(d.length()-10):d; }
    private static boolean eq(String a,String b){ return MessageDigest.isEqual((a==null?"":a).getBytes(StandardCharsets.UTF_8),(b==null?"":b).getBytes(StandardCharsets.UTF_8)); }
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final SecureRandom PASSWORD_RANDOM = new SecureRandom();
    private static String legacyHash(String v){ try{ MessageDigest md=MessageDigest.getInstance("SHA-256"); return HexFormat.of().formatHex(md.digest((v==null?"":v).getBytes(StandardCharsets.UTF_8))); }catch(Exception e){throw new IllegalStateException(e);} }
    private static String hash(String v){
        byte[] salt = new byte[16]; PASSWORD_RANDOM.nextBytes(salt);
        byte[] derived = derive(v == null ? "" : v, salt, PASSWORD_ITERATIONS);
        return "pbkdf2$" + PASSWORD_ITERATIONS + "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }
    private static byte[] derive(String password, byte[] salt, int iterations){
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException("Password hashing is unavailable.", e); }
        finally { spec.clearPassword(); }
    }
    private static boolean passwordMatches(String stored, String password){
        if(stored==null||password==null)return false;
        if(!stored.startsWith("pbkdf2$")) return eq(stored, legacyHash(password));
        try {
            String[] parts=stored.split("\\$",-1); if(parts.length!=4)return false;
            int iterations=Integer.parseInt(parts[1]); if(iterations<100_000||iterations>1_000_000)return false;
            byte[] salt=Base64.getUrlDecoder().decode(parts[2]); byte[] expected=Base64.getUrlDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected,derive(password,salt,iterations));
        } catch(Exception e){return false;}
    }
    private static String normEmail(String e){return e==null?"":e.trim().toLowerCase(Locale.ROOT);}

    public AppUser signup(Body b){
        String name=b.str("name").replaceAll("\\s+"," ").trim(), phone=normalizePhone(b.str("phone")), email=normEmail(b.str("email")), password=b.str("password"), city=b.str("city");
        if(name.length()<2||name.length()>80)throw new ApiException(400,"Enter your full name.");
        if(!PHONE.matcher(phone).matches())throw new ApiException(400,"Enter a valid 10-digit Indian mobile number.");
        if(!EMAIL.matcher(email).matches())throw new ApiException(400,"Enter a valid email address.");
        if(password.length()<6)throw new ApiException(400,"Password must contain at least 6 characters.");
        if(users.findByPhone(phone).isPresent()||users.findByEmailIgnoreCase(email).isPresent())throw new ApiException(409,"An account already exists with this email or mobile number.");
        AppUser u=new AppUser(); u.name=name;u.phone=phone;u.email=email;u.passwordHash=hash(password);u.role="CUSTOMER";u.city=city;u.status="ACTIVE";u.joined=time.today();
        try{return users.save(u);}catch(DataIntegrityViolationException e){throw new ApiException(409,"An account already exists with this email or mobile number.");}
    }

    /** Resets a password without OTP, requiring both registered contact fields to match. */
    public void resetPassword(String email,String phone,String newPassword){
        String e=normEmail(email), p=normalizePhone(phone), key=e+":"+p; long now=System.currentTimeMillis();
        long[] f=resetFailures.get(key);
        if(f!=null&&f[1]>now)throw new ApiException(429,"Too many reset attempts. Please try again later.");
        if(!EMAIL.matcher(e).matches()||!PHONE.matcher(p).matches()||newPassword==null||newPassword.length()<8){
            recordResetFailure(key,now); throw new ApiException(400,"Enter a valid registered email, mobile number, and password of at least 8 characters.");
        }
        AppUser u=users.findByEmailIgnoreCase(e).orElse(null);
        if(u==null||!normalizePhone(u.phone).equals(p)||!"ACTIVE".equals(u.status)){
            recordResetFailure(key,now); throw new ApiException(400,"The registered email and mobile number could not be verified.");
        }
        u.passwordHash=hash(newPassword); users.save(u); resetFailures.remove(key);
        // Revoke existing sessions after password change so prior tokens cannot remain active.
        sessions.findAll().stream().filter(session -> Objects.equals(session.userId,u.id)).forEach(session -> sessions.deleteById(session.token));
    }
    private void recordResetFailure(String key,long now){
        long[] cur=resetFailures.computeIfAbsent(key,k->new long[2]);
        synchronized(cur){cur[0]++;if(cur[0]>=5){cur[1]=now+600000;cur[0]=0;}}
    }

    public Map<String,Object> login(String email,String password){
        String e=normEmail(email), key=e; long now=System.currentTimeMillis(); long[] f=failures.get(key); if(f!=null&&f[1]>now)throw new ApiException(429,"Too many failed attempts. Try again later.");
        AppUser u=users.findByEmailIgnoreCase(e).orElse(null);
        if(adminPassword != null && !adminPassword.isBlank() && e.equals(normEmail(adminEmail)) && eq(password,adminPassword) && (u==null || "ADMIN".equalsIgnoreCase(u.role))){
            if(u==null){u=users.findByPhone(adminPhone).orElse(null);}
            if(u==null){u=new AppUser();u.name="System Administrator";u.phone=adminPhone;u.email=e;u.role="ADMIN";u.status="ACTIVE";u.joined=time.today();}
            u.role="ADMIN";u.email=e;u.passwordHash=hash(adminPassword);u.status="ACTIVE";u=users.save(u);
        }
        if(u==null || !"ACTIVE".equals(u.status) || u.passwordHash==null || !passwordMatches(u.passwordHash,password)){
            long[] cur=failures.computeIfAbsent(key,k->new long[2]);synchronized(cur){cur[0]++;if(cur[0]>=5){cur[1]=now+300000;cur[0]=0;}}throw new ApiException(401,"Invalid email or password.");
        }
        failures.remove(key);
        if (!u.passwordHash.startsWith("pbkdf2$")) { u.passwordHash = hash(password); u = users.save(u); }
        String role="ADMIN".equalsIgnoreCase(u.role)?"admin":"customer";
        String token=newSession(role,u.id,e,u.phone,Duration.ofHours(8));
        return J.map("user",J.user(u),"token",token,"role",u.role);
    }
    public AppUser updateProfile(AppUser u,Body b){String name=b.str("name").replaceAll("\\s+"," ").trim(),email=normEmail(b.str("email")),city=b.str("city");if(name.length()<2||name.length()>80)throw new ApiException(400,"Enter your full name.");if(!email.isEmpty()&&!EMAIL.matcher(email).matches())throw new ApiException(400,"Enter a valid email address.");if(city.length()>80)throw new ApiException(400,"City name is too long.");u.name=name;u.email=email;u.city=city;return users.save(u);}
    private String newSession(String role,Long userId,String identity,String phone,Duration ttl){sessions.deleteByExpiresAtBefore(time.now());AuthSession s=new AuthSession();s.token=J.token(32);s.role=role;s.userId=userId;s.identity=identity;s.phone=normalizePhone(phone);s.expiresAt=time.now().plus(ttl);sessions.save(s);return s.token;}
    public AuthSession session(HttpServletRequest req){String h=req.getHeader("Authorization");if(h==null||!h.startsWith("Bearer "))return null;String t=h.substring(7).trim();if(t.isEmpty())return null;return sessions.findById(t).filter(x->x.expiresAt.isAfter(time.now())).orElse(null);}
    public void logout(HttpServletRequest req){AuthSession s=session(req);if(s!=null)sessions.deleteById(s.token);}
    public AuthSession requireAny(HttpServletRequest req){AuthSession s=session(req);if(s==null)throw new ApiException(401,"Please sign in to continue.");return s;}
    public AuthSession requireAdmin(HttpServletRequest req){AuthSession s=requireAny(req);if(!"admin".equals(s.role))throw new ApiException(403,"Admin access required.");return s;}
    public AppUser requireBookingUser(HttpServletRequest req){AuthSession s=requireAny(req);if(!"customer".equals(s.role)||s.userId==null)throw new ApiException(403,"Please sign in with a customer account before booking.");AppUser u=users.findById(s.userId).orElseThrow(()->new ApiException(401,"Account not found."));if(!"ACTIVE".equals(u.status))throw new ApiException(403,"This account has been deactivated.");return u;}
    public AppUser requireCustomer(HttpServletRequest req){AuthSession s=requireAny(req);if(!"customer".equals(s.role)||s.userId==null)throw new ApiException(403,"Customer account required.");AppUser u=users.findById(s.userId).orElseThrow(()->new ApiException(401,"Account not found."));if(!"ACTIVE".equals(u.status))throw new ApiException(403,"This account has been deactivated.");return u;}
}
