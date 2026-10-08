CREATE TABLE storage_usage(singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),hot_bytes BIGINT NOT NULL DEFAULT 0 CHECK(hot_bytes>=0),max_hot_bytes BIGINT NOT NULL DEFAULT 0 CHECK(max_hot_bytes>=0),max_database_bytes BIGINT NOT NULL DEFAULT 0 CHECK(max_database_bytes>=0),admission_blocked BOOLEAN NOT NULL DEFAULT false);
INSERT INTO storage_usage(singleton,hot_bytes) SELECT true,
 coalesce((SELECT sum(octet_length(payload_bytes)) FROM message),0)+coalesce((SELECT sum(octet_length(payload_bytes)) FROM message_delivery_snapshot),0);
CREATE FUNCTION m3_hot_usage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE delta BIGINT; used BIGINT; limit_bytes BIGINT;
BEGIN
 delta:=CASE WHEN TG_OP='DELETE' THEN 0 ELSE coalesce(octet_length(NEW.payload_bytes),0) END
       -CASE WHEN TG_OP='INSERT' THEN 0 ELSE coalesce(octet_length(OLD.payload_bytes),0) END;
 UPDATE storage_usage SET hot_bytes=hot_bytes+delta WHERE singleton RETURNING hot_bytes,max_hot_bytes INTO used,limit_bytes;
 IF delta>0 AND limit_bytes>0 AND used>limit_bytes THEN RAISE EXCEPTION 'M3 hot storage quota exceeded' USING ERRCODE='53000'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END; $$;
CREATE TRIGGER m3_hot_usage_message AFTER INSERT OR UPDATE OF payload_bytes OR DELETE ON message FOR EACH ROW EXECUTE FUNCTION m3_hot_usage();
CREATE TRIGGER m3_hot_usage_delivery AFTER INSERT OR UPDATE OF payload_bytes OR DELETE ON message_delivery_snapshot FOR EACH ROW EXECUTE FUNCTION m3_hot_usage();
