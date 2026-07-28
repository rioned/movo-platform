# MOVO Portal Restoration Implementation Plan

**Goal:** Restore deployable Customer, Rider, and Business portals while preserving the existing Express/SQLite API.

**Architecture:** Each portal is a static HTML/CSS/JavaScript SPA served by Express. It stores a role-specific JWT locally, calls the existing REST API, and renders only operations authorized for that role. Registration and login share the same auth endpoint; `OTP_TEST_MODE=true` remains intentionally enabled for the current test environment.

**Acceptance criteria:**
- Every portal exposes Register and Sign In.
- Customer can create, view, and track deliveries and update profile.
- Rider can set availability, view/accept/update assigned deliveries, and view earnings/performance.
- Business can sign in, see dashboard and deliveries, and update business profile.
- Admin login/navigation remain working.
- PM2 deployment configuration and health endpoint are verified.

## Tasks
- [ ] Add API regression tests for role authorization and the core delivery lifecycle.
- [ ] Implement Customer portal UI and browser test.
- [ ] Implement Rider portal UI and browser test.
- [ ] Implement Business portal UI and browser test.
- [ ] Add production PM2 configuration, environment template, and deployment verification.
