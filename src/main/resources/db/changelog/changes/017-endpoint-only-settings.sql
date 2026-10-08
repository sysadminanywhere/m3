-- Deserialization is fixed to bytes; decoding belongs to stored charset and rule processing.
DELETE FROM channel_properties WHERE property_key IN ('keyDeserializer','valueDeserializer');
-- The enabled flag controls outbound availability only; inbound loading is controlled by rules.
UPDATE channel_settings SET enabled=true WHERE direction='INBOUND';
