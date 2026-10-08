-- Recover the actual destination from copies created by inbound routing rules.
-- Manual forwarding does not change the original message's routing history.
WITH routed_targets AS (
    SELECT original.message_id, min(copy.target_system) AS target_system
    FROM message original
    JOIN message_metadata link ON link.key='sourceMessageId' AND link.value=original.message_id::text
    JOIN message copy ON copy.message_id=link.message_id AND copy.direction='OUTBOUND'
    WHERE original.direction='INBOUND'
      AND copy.target_system IS NOT NULL AND copy.target_system<>''
      AND EXISTS (
          SELECT 1 FROM rule_execution_job job JOIN rule r ON r.rule_id=job.rule_id
          WHERE job.message_id=copy.message_id AND r.rule_type='INBOUND'
      )
    GROUP BY original.message_id
    HAVING count(DISTINCT copy.target_system)=1
)
UPDATE message original SET target_system=route.target_system
FROM routed_targets route
WHERE original.message_id=route.message_id
  AND (original.target_system IS NULL OR original.target_system='');
