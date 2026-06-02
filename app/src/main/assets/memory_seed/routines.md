# Routines

## Commute to Work

Use:
- Work from [[main.md]]
- Default commute mode from [[main.md]]
- Commute playlist from [[main.md]]

Steps:
1. If Work is missing, ask the user for it.
2. If Default commute mode is missing, ask the user for it.
3. Call check_calendar with range today.
4. Call read_notifications for Slack from the last 180 minutes, limit 10.
5. Call check_emails for the last 24 hours, max 10.
6. Call maps_travel_time to Work using the default commute mode.
7. If Commute playlist is present, start it with spotify_play_playlist.
8. Start navigation to Work with start_navigation.
9. Finish with one concise summary covering calendar, Slack, Gmail, travel time, playlist, and navigation status.

## Memory Onboarding

This routine is handled deterministically by the app setup onboarding form.

Collect these optional fields:
1. Name
2. Assistant personality
3. Home location
4. Work location
5. Default messaging app

Save answers into [[main.md]] and [[soul.md]]. If the user leaves a field blank, leave that memory field blank and record the skipped step in [[main.md]]. When the form is submitted, set onboarding Status to complete so memory onboarding is never shown again.
