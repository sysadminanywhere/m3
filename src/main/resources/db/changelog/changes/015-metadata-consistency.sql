ALTER TABLE message_metadata ALTER COLUMN value TYPE TEXT;
DELETE FROM message_metadata older USING message_metadata newer
WHERE older.message_id=newer.message_id AND older.key=newer.key AND older.metadata_id<newer.metadata_id;
CREATE UNIQUE INDEX IF NOT EXISTS uk_message_metadata_key ON message_metadata(message_id,key);
