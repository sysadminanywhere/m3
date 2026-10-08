ALTER TABLE message ADD COLUMN IF NOT EXISTS payload_bytes BYTEA;
ALTER TABLE message ADD COLUMN IF NOT EXISTS charset VARCHAR(64);
ALTER TABLE message ADD COLUMN IF NOT EXISTS charset_source VARCHAR(32);
ALTER TABLE message ADD COLUMN IF NOT EXISTS payload_format VARCHAR(16);
ALTER TABLE message ADD COLUMN IF NOT EXISTS payload TEXT;

-- Keep the legacy column for migration diagnostics. New application writes use bytea only.
DO $$
DECLARE item RECORD; original BYTEA;
BEGIN
    FOR item IN SELECT m.message_id, m.payload,
        EXISTS(SELECT 1 FROM message_metadata md WHERE md.message_id=m.message_id AND md.key='encoding' AND md.value='base64') AS is_base64
        FROM message m WHERE m.payload_bytes IS NULL
    LOOP
        IF item.is_base64 THEN
            BEGIN
                original := decode(item.payload,'base64');
            EXCEPTION WHEN invalid_parameter_value THEN
                original := NULL;
            END;
            IF original IS NOT NULL THEN
                UPDATE message SET payload_bytes=original, payload_format='BASE64', charset_source='LEGACY_BYTES',
                    charset=(SELECT CASE WHEN length(md.value)<=64 THEN md.value ELSE NULL END FROM message_metadata md
                        WHERE md.message_id=item.message_id AND md.key='charset' LIMIT 1) WHERE message_id=item.message_id;
            ELSE
                UPDATE message SET payload_bytes=convert_to(item.payload,'UTF8'), payload_format='TEXT',
                    charset='UTF-8', charset_source='LEGACY_INVALID_BASE64' WHERE message_id=item.message_id;
            END IF;
        ELSE
            UPDATE message SET payload_bytes=convert_to(item.payload,'UTF8'), payload_format='TEXT',
                charset='UTF-8', charset_source='LEGACY_TEXT_UTF8' WHERE message_id=item.message_id;
        END IF;
    END LOOP;
END $$;

ALTER TABLE message ALTER COLUMN payload DROP NOT NULL;
ALTER TABLE message ALTER COLUMN payload_bytes SET NOT NULL;
ALTER TABLE message ALTER COLUMN charset_source SET NOT NULL;
ALTER TABLE message ALTER COLUMN payload_format SET NOT NULL;
ALTER TABLE message ADD CONSTRAINT chk_message_payload_format CHECK (payload_format IN ('TEXT','BASE64'));
ALTER TABLE message ADD CONSTRAINT chk_message_text_charset CHECK (payload_format <> 'TEXT' OR charset IS NOT NULL);
