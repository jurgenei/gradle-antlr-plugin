INSERT INTO "audit_log" (event_id, message) VALUES (42, 'created');
SELECT SUM(amount) AS total, customer_id FROM orders WHERE customer_id = 99 ORDER BY total DESC;

