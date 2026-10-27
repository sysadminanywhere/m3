# Local usage summary

Administrators can open **Settings → Local usage summary** to see aggregate counts derived from existing records. No usage data is transmitted by this feature and it does not store a separate usage history.

The message figures count records created in the last 30 days that are still retained. Active message days count distinct UTC dates on which at least one such record was created; this is not a count of users, sessions or installations. Inbound and outbound copies are separate records. Sent and failed refer to the current status of outbound records created in the period, not a count of delivery attempts. Pending records are not included in either figure. Retention and deletion can reduce historical counts, and a status can change after the record was created.

Enabled rules, channel types and rule action types describe the current configuration, not activity during the period. The summary does not include payloads, metadata, channel names, addresses, user names or diagnostic text. An empty summary does not prove that an installation was never used.

Remote GA4 reporting and its consent flow are not implemented. This local summary does not request consent or send events to Google.
