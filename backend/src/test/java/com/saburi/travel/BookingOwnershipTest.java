package com.saburi.travel;

import com.saburi.travel.model.AppUser;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.service.BookingService;
import com.saburi.travel.service.JourneyService;
import com.saburi.travel.service.SettingService;
import com.saburi.travel.util.ApiException;
import com.saburi.travel.util.Time;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class BookingOwnershipTest {
    @Test
    void personalBookingsRejectAnotherCustomersPhone() {
        Repos.BookingRepo bookings = mock(Repos.BookingRepo.class);
        BookingService service = service(bookings);
        AppUser customer = new AppUser();
        customer.id = 7L;
        customer.phone = "9876543210";

        ApiException error = assertThrows(ApiException.class, () -> service.myBookings(customer, "9123456789"));

        assertEquals(403, error.status);
        verify(bookings, never()).findByUserIdOrderByIdDesc(any());
    }

    @Test
    void personalBookingsAreQueriedByAuthenticatedUserId() {
        Repos.BookingRepo bookings = mock(Repos.BookingRepo.class);
        when(bookings.findByUserIdOrderByIdDesc(7L)).thenReturn(List.of());
        BookingService service = service(bookings);
        AppUser customer = new AppUser();
        customer.id = 7L;
        customer.phone = "9876543210";

        service.myBookings(customer, null);

        verify(bookings).findByUserIdOrderByIdDesc(7L);
    }

    private static BookingService service(Repos.BookingRepo bookings) {
        return new BookingService(
                mock(Repos.ScheduleRepo.class), mock(Repos.BusRepo.class), mock(Repos.RouteRepo.class),
                bookings, mock(Repos.SeatRepo.class), mock(Repos.HoldRepo.class),
                mock(Repos.SegmentLockRepo.class), mock(JourneyService.class),
                mock(Repos.PaymentRepo.class), mock(Repos.RefundRepo.class),
                mock(Time.class), mock(SettingService.class), 120, 24, 6, "");
    }
}
