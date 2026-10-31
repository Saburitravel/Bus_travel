package com.saburi.travel;

import com.saburi.travel.model.AppUser;
import com.saburi.travel.model.AuthSession;
import com.saburi.travel.service.AuthService;
import com.saburi.travel.service.BookingService;
import com.saburi.travel.util.ApiException;
import com.saburi.travel.web.AuthController;
import com.saburi.travel.web.BookingController;
import com.saburi.travel.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiFlowControllerTest {
    private AuthService auth;
    private BookingService bookings;
    private MockMvc authMvc;
    private MockMvc bookingMvc;

    @BeforeEach void setUp() {
        auth = mock(AuthService.class);
        bookings = mock(BookingService.class);
        authMvc = MockMvcBuilders.standaloneSetup(new AuthController(auth))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        bookingMvc = MockMvcBuilders.standaloneSetup(new BookingController(bookings, auth))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void loginUsesEmailAndPasswordWithoutOtp() throws Exception {
        when(auth.login("guest@example.com", "correct-password")).thenReturn(Map.of("token", "session", "role", "CUSTOMER"));
        authMvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"guest@example.com\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value("session"));
        verify(auth).login("guest@example.com", "correct-password");
    }

    @Test void registrationDelegatesToBackendValidation() throws Exception {
        AppUser user = new AppUser(); user.name = "Test Customer"; user.phone = "9876543210"; user.email = "guest@example.com"; user.role = "CUSTOMER"; user.status = "ACTIVE";
        when(auth.signup(any())).thenReturn(user);
        authMvc.perform(post("/api/auth/signup").contentType("application/json")
                .content("{\"name\":\"Test Customer\",\"phone\":\"9876543210\",\"email\":\"guest@example.com\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("guest@example.com"));
        verify(auth).signup(any());
    }

    @Test void profileRequiresAuthenticatedCustomer() throws Exception {
        doThrow(new ApiException(401, "Please sign in to continue.")).when(auth).requireCustomer(any());
        authMvc.perform(get("/api/users/profile"))
                .andExpect(status().isUnauthorized());
        verify(auth).requireCustomer(any());
    }

    @Test void profileReturnsAuthenticatedCustomerDetails() throws Exception {
        AppUser customer = new AppUser();
        customer.id = 7L; customer.name = "Test Customer"; customer.phone = "9876543210";
        customer.email = "guest@example.com"; customer.role = "CUSTOMER"; customer.status = "ACTIVE";
        when(auth.requireCustomer(any())).thenReturn(customer);
        authMvc.perform(get("/api/users/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("9876543210"))
                .andExpect(jsonPath("$.email").value("guest@example.com"));
        verify(auth).requireCustomer(any());
    }

    @Test void guestBookingRequiresBookingSessionAndReturnsCreatedBooking() throws Exception {
        AppUser guest = new AppUser(); guest.phone = "9876543210"; guest.email = "guest@example.com"; guest.role = "GUEST";
        when(auth.requireBookingUser(any())).thenReturn(guest);
        when(bookings.create(eq(guest), any())).thenReturn(Map.of("id", "SST-000001", "guestAccessToken", "secret"));
        bookingMvc.perform(post("/api/bookings").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guestAccessToken").value("secret"));
        verify(bookings).create(eq(guest), any());
    }

    @Test void personalBookingsRequireAuthenticatedCustomer() throws Exception {
        doThrow(new ApiException(401, "Please sign in to continue.")).when(auth).requireBookingUser(any());
        bookingMvc.perform(get("/api/bookings/my").param("phone", "9876543210"))
                .andExpect(status().isUnauthorized());
        verify(auth).requireBookingUser(any());
        verify(bookings, never()).myBookings(any(), anyString());
    }

    @Test void personalBookingsUseTheAuthenticatedCustomer() throws Exception {
        AppUser customer = new AppUser();
        customer.id = 7L; customer.phone = "9876543210"; customer.role = "CUSTOMER";
        when(auth.requireBookingUser(any())).thenReturn(customer);
        when(bookings.myBookings(customer, "9876543210")).thenReturn(List.of(Map.of("id", "SST-000001")));
        bookingMvc.perform(get("/api/bookings/my").param("phone", "9876543210"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("SST-000001"));
        verify(bookings).myBookings(customer, "9876543210");
    }

    @Test void guestUpiRequestPassesSecretInHeader() throws Exception {
        when(auth.session(any())).thenReturn(null);
        when(bookings.paymentInfo(eq(1L), any(AppUser.class), eq("access-secret")))
                .thenReturn(Map.of("amount", 499, "upiUri", "upi://pay"));
        bookingMvc.perform(get("/api/payments/upi?bookingId=1").header("X-Guest-Booking-Token", "access-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(499));
        verify(bookings).paymentInfo(eq(1L), any(AppUser.class), eq("access-secret"));
    }

    @Test void guestCancellationPassesSecretAndBookingId() throws Exception {
        when(auth.session(any())).thenReturn(null);
        when(bookings.cancel(eq(1L), any(AppUser.class), eq("access-secret"))).thenReturn(Map.of("status", "Cancelled"));
        bookingMvc.perform(post("/api/bookings/1/cancel").header("X-Guest-Booking-Token", "access-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Cancelled"));
        verify(bookings).cancel(eq(1L), any(AppUser.class), eq("access-secret"));
    }

    @Test void adminBookingListIsBlockedWhenAdminCheckFails() throws Exception {
        doThrow(new ApiException(403, "Admin access required.")).when(auth).requireAdmin(any());
        bookingMvc.perform(get("/api/bookings/all"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Admin access required."));
        verify(bookings, never()).allBookings();
    }

    @Test void adminBookingListPassesAfterAdminCheck() throws Exception {
        when(auth.requireAdmin(any())).thenReturn(new AuthSession());
        when(bookings.allBookings()).thenReturn(List.of(Map.of("id", "SST-000001")));
        bookingMvc.perform(get("/api/bookings/all"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("SST-000001"));
        verify(auth).requireAdmin(any());
    }
}
