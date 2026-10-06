-- First-login onboarding tour: when the user finished or skipped it. Additive:
-- nullable column. Existing users are backfilled to now() so only accounts
-- created after this migration see the tour.
ALTER TABLE users ADD COLUMN onboarding_tour_seen_at TIMESTAMP WITH TIME ZONE;
UPDATE users SET onboarding_tour_seen_at = now();
