CREATE TABLE IF NOT EXISTS rule_loading_properties (
    rule_id BIGINT NOT NULL REFERENCES rule(rule_id) ON DELETE CASCADE,
    property_key VARCHAR(255) NOT NULL,
    property_value TEXT,
    PRIMARY KEY(rule_id,property_key)
);
INSERT INTO rule_loading_properties(rule_id,property_key,property_value)
SELECT r.rule_id,p.property_key,p.property_value FROM rule r
JOIN channel_properties p ON p.channel_id=r.source_channel_id
WHERE r.rule_type='INBOUND' AND p.property_key IN
('pollingInterval','filePattern','recursive','minFileAgeMs','deleteAfterProcessing','deleteRemoteFiles','groupId','autoOffsetReset')
ON CONFLICT (rule_id,property_key) DO NOTHING;
DELETE FROM channel_properties WHERE property_key IN
('pollingInterval','filePattern','recursive','minFileAgeMs','deleteAfterProcessing','deleteRemoteFiles','groupId','autoOffsetReset');
