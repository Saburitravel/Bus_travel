package com.saburi.travel.util;

import java.math.BigDecimal;
import java.util.*;

/** Lenient accessor over a JSON request body. */
public class Body {
    private final Map<String, Object> m;

    public Body(Map<String, Object> m) { this.m = m == null ? new HashMap<>() : m; }

    public String str(String k) { Object v = m.get(k); return v == null ? "" : v.toString().trim(); }
    public boolean has(String k) { return !str(k).isEmpty(); }
    public boolean present(String k) { return m.containsKey(k); }

    public long lng(String k) {
        try { return new BigDecimal(str(k)).longValueExact(); }
        catch (Exception e) { throw new ApiException(400, "Invalid or missing " + k); }
    }
    public int integer(String k) {
        long v = lng(k);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw new ApiException(400, "Invalid " + k);
        return (int) v;
    }
    public double dbl(String k) {
        try {
            double d = Double.parseDouble(str(k));
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) { throw new ApiException(400, "Invalid or missing " + k); }
    }
    public Double dblOrNull(String k) { return has(k) ? dbl(k) : null; }
    public boolean bool(String k) { return Boolean.parseBoolean(str(k)); }

    public List<String> strList(String k) {
        List<String> out = new ArrayList<>();
        if (m.get(k) instanceof List<?> l) for (Object o : l) out.add(o == null ? "" : o.toString().trim());
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<Body> objList(String k) {
        List<Body> out = new ArrayList<>();
        if (m.get(k) instanceof List<?> l)
            for (Object o : l) if (o instanceof Map<?, ?> mm) out.add(new Body((Map<String, Object>) mm));
        return out;
    }
}
