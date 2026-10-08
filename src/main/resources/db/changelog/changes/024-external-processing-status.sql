ALTER TABLE message DROP CONSTRAINT IF EXISTS message_status_check;
ALTER TABLE message ADD CONSTRAINT message_status_check
    CHECK (status IN ('LOADED', 'PROCESSING', 'PROCESSING_FAILED', 'PENDING', 'SENT', 'FAILED', 'PROCESSED'));
-- Retain existing statuses: prior automatic processing results cannot be reinterpreted as callbacks.
