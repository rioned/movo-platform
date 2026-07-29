# Rider Profile Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for task-by-task execution.

**Goal:** Make uploaded rider identity visible in the authenticated rider dashboard: profile photo at top-left plus balance, rating, delivery count, and account state.

**Architecture:** The mobile home DTO will provide a short-lived authenticated profile-image endpoint and server-authoritative rider metrics. The app will render a compact profile header using Coil with the JWT header; failed image loads retain an initials avatar and upload-state feedback.

**Tech Stack:** Express, SQLite, Kotlin Compose Material 3, Coil Compose.

## Tasks

### Task 1: Extend the secure profile contract
- [ ] Add `profile` to the private rider-document allow-list.
- [ ] Return `profile_photo_url`, `total_earnings`, `avg_rating`, `rating_count`, and `total_deliveries` from `/api/mobile/v1/rider/home`.
- [ ] Add a Node regression test asserting the mobile DTO does not expose a filesystem path and contains the profile URL only for an uploaded photo.

### Task 2: Render a durable rider identity header
- [ ] Add Coil Compose dependency.
- [ ] Load the profile image using the existing JWT only in the authenticated app.
- [ ] Render a green header with photo/initials fallback, rider label, online state, balance, rating, and delivery count.
- [ ] Retain current upload controls and render uploaded/pending feedback.

### Task 3: Verify on the physical device
- [ ] Build/install the APK on `192.168.0.185:46381`.
- [ ] Use the controlled photo fixture to confirm the header image request is authenticated and visible.
- [ ] Confirm metrics are rendered from the live `/api/mobile/v1/rider/home` response.
- [ ] Run Node tests, syntax check, and Gradle build.
