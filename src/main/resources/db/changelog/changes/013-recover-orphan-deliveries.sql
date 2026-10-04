-- Repair outbound copies created by inbound routing before delivery jobs were added.
-- Only an unambiguous active rule may own the already transformed bytes.
WITH candidates AS (
    SELECT m.message_id, min(r.rule_id) AS rule_id, min(r.worker_pool_id) AS pool_id,
           min(a.target_channel_id) AS channel_id
    FROM message m
    JOIN channel_settings s ON s.name=m.source_system
    JOIN rule r ON r.source_channel_id=s.channel_id AND r.rule_type='INBOUND' AND r.enabled=true
    JOIN rule_action a ON a.rule_id=r.rule_id AND a.action_type='ROUTE'
    JOIN channel_settings d ON d.channel_id=a.target_channel_id AND d.name=m.target_system
    WHERE m.direction='OUTBOUND' AND m.status='PENDING' AND r.worker_pool_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM rule_execution_job j WHERE j.message_id=m.message_id)
      AND NOT EXISTS (SELECT 1 FROM message_metadata x WHERE x.message_id=m.message_id
          AND x.key='loadingRuleId' AND x.value<>r.rule_id::text)
    GROUP BY m.message_id HAVING count(DISTINCT r.rule_id)=1
)
INSERT INTO rule_execution_job(message_id,rule_id,worker_pool_id,status,created_at,result)
SELECT m.message_id,c.rule_id,c.pool_id,'PENDING',now(),jsonb_build_object(
    'channelId',c.channel_id,'body',replace(encode(m.payload_bytes,'base64'),E'\n',''),
    'payloadType',m.payload_type,'metadata',
    COALESCE((SELECT jsonb_object_agg(x.key,x.value) FROM message_metadata x
              WHERE x.message_id=m.message_id AND x.key NOT IN ('encoding','id','timestamp')),'{}'::jsonb)
        || jsonb_build_object('m3MessageId',m.message_id::text,'m3RuleId',c.rule_id::text))::text
FROM candidates c JOIN message m ON m.message_id=c.message_id;

-- Do not guess a destination when the original routing rule cannot be identified.
INSERT INTO message_metadata(message_id,key,value)
SELECT m.message_id,'deliveryError','No unambiguous routing rule for legacy outbound message; resubmit with an explicit ruleId'
FROM message m WHERE m.direction='OUTBOUND' AND m.status='PENDING'
 AND NOT EXISTS (SELECT 1 FROM rule_execution_job j WHERE j.message_id=m.message_id)
 AND NOT EXISTS (SELECT 1 FROM message_metadata x WHERE x.message_id=m.message_id AND x.key='deliveryError');
UPDATE message m SET status='FAILED',processed_at=now()
WHERE m.direction='OUTBOUND' AND m.status='PENDING'
 AND NOT EXISTS (SELECT 1 FROM rule_execution_job j WHERE j.message_id=m.message_id);
