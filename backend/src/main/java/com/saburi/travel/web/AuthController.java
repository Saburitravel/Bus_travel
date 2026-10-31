package com.saburi.travel.web;

import com.saburi.travel.model.AppUser;
import com.saburi.travel.service.AuthService;
import com.saburi.travel.util.Body;
import com.saburi.travel.util.J;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth){this.auth=auth;}

    @PostMapping("/auth/signup") public Map<String,Object> signup(@RequestBody Map<String,Object> body){return J.user(auth.signup(new Body(body)));}
    @PostMapping("/auth/login") public Map<String,Object> login(@RequestBody Map<String,Object> body){Body b=new Body(body);return auth.login(b.str("email"),b.str("password"));}
    @PostMapping("/auth/forgot-password") public Map<String,Object> forgotPassword(@RequestBody Map<String,Object> body){Body b=new Body(body);auth.resetPassword(b.str("email"),b.str("phone"),b.str("password"));return J.map("ok",true,"message","Password reset successfully.");}
    @PostMapping("/auth/logout") public Map<String,Object> logout(HttpServletRequest req){auth.logout(req);return J.map("ok",true);}
    @GetMapping("/users/profile") public Map<String,Object> profile(HttpServletRequest req){return J.user(auth.requireCustomer(req));}
    @PutMapping("/users/profile") public Map<String,Object> profile(HttpServletRequest req,@RequestBody Map<String,Object> body){AppUser u=auth.requireCustomer(req);return J.user(auth.updateProfile(u,new Body(body)));}
}
