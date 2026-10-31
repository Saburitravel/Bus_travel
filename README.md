# Saburi Swami Travel

Java full-stack bus booking system. The frontend is served by Spring Boot from `backend/src/main/resources/static/`. The backend uses Spring Data MongoDB and expects MongoDB Atlas (or another reachable replica-set MongoDB deployment).

## Requirements

- JDK 26
- Apache Maven 3.6.3 or newer
- A MongoDB Atlas cluster / replica set
- Admin credentials configured securely in the environment

## Configure and run on Windows PowerShell

From the project root, set environment variables in the same PowerShell session. Do not paste real credentials into source files or commit them.

```powershell
$env:MONGODB_URI = 'mongodb+srv://<db-user>:<URL-encoded-password>@<cluster-host>/saburi_travel?retryWrites=true&w=majority&appName=Cluster0'
$env:MONGODB_DATABASE = 'saburi_travel'
$env:ADMIN_EMAIL = 'admin@example.com'
$env:ADMIN_PASSWORD = '<long-unique-random-password>'
$env:ADMIN_PHONE = '<10-digit-admin-mobile>'
$env:ADMIN_UPI_ID = '<business-upi-id@bank>'
cd backend
mvn clean verify
mvn spring-boot:run
```

Open `
`. `MONGODB_URI` is mandatory; the app intentionally does not silently fall back to a local database. `ADMIN_EMAIL`, `ADMIN_PASSWORD`, and `ADMIN_PHONE` must be configured for admin login. `ADMIN_UPI_ID` must be configured before the server can generate a UPI payment link. `PORT` is optional and defaults to 8080; `MONGODB_DATABASE` is optional and defaults to `saburi_travel`. See `.env.example` for the full template. For automated startup, `../run-backend.ps1` runs verification first and starts the application only if Maven verification succeeds.

## Before production

Read `DEPLOYMENT_VERIFICATION.md`, `MONGODB_MIGRATION.md`, and `docs/RUN_GUIDE.md`. Set all secrets in the hosting provider's environment settings. Configure the Atlas network access list to the deployment's outbound IP. Rotate any credential that has appeared in a chat or terminal log.

## Important limits

This source archive has not been built against Maven Central in the current audit environment because Maven is unavailable and external DNS resolution is blocked. It is a deployment candidate, not a production-certified build. Atlas TLS connectivity, transaction behavior, payment-provider verification, and the end-to-end booking lifecycle must pass on the target network before accepting real customers or payments.

## Booking and payment status

- A newly created booking starts as `Pending` and remains pending in booking lists / My Bookings until an administrator reviews and confirms it.
- A UPI deep link only opens a payment app; it is not proof that a payment reached the business account. This build does not independently verify UPI payments with a bank/payment provider.
- The admin confirmation action changes the booking to `Confirmed` and marks the payment `SUCCESS`. The administrator must confirm only after independently checking receipt of payment. If the admin rejects the pending booking, it becomes `Cancelled` and its seat locks are released.
- Pending bookings must not be presented as confirmed tickets. Ticket verification is valid only when the booking status is `Confirmed`.

## Authentication after OTP removal

- Account login uses email + password and returns a server-side session token.
- Guest checkout requests a limited guest session using the entered phone number and email; it does not send email or verify ownership.
- OTP endpoints, OTP persistence, and SMTP configuration have been removed.
- Password recovery by email is not available without OTP. Use a secure administrator-assisted reset process; do not expose an unauthenticated password-reset endpoint.
- Because guest identity is not verified, avoid treating guest booking details as proof of identity for sensitive changes or refunds.


## Payment methods

- UPI is the only enabled payment method. It opens the configured UPI app with the booking amount.
- UPI payment must be manually verified by the admin because a deep link cannot independently confirm that funds were transferred.
- Card and Net Banking are disabled until a payment provider is integrated and server-side payment reconciliation is implemented.
- A ticket is available only after payment verification and admin booking confirmation.
