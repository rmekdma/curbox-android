# Release notes

## Neutral app rules cutover

Existing coupled app groups are imported once into reusable app groups and app rules. The import
uses stable ids derived from the old ids, so a retry is safe and does not duplicate restrictions.
Pending app group changes are converted to the app rule pending path before they are applied.

The old app group field remains readable for API and sync compatibility, but the service and product
screens use the neutral app group and app rule graph. App rules describe the scope, use day,
usage condition, allowance, earned allowance, guardian extra time, skip restriction, and usage
reset in one place. A full day or zero minute allowance is used for old on open and interval
behaviors rather than a separate rule type.

Upgrading to this release updates the Room database schema for accurate foreground session tracking
and use-day calculation. Because Curbox uses Room destructive fallback migration, upgrading from
an older installation resets local usage session logs and historical aggregates on the device.
Please export or sync any usage data you wish to preserve prior to upgrading.
