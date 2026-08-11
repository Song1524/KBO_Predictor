-- V3 created a local development account before environment profiles existed.
-- Existing local databases retain it. Fresh prod/test databases remove it after
-- all dependent tables have been created. The placeholder is controlled only by
-- application-local/prod/test.yaml.
DELETE point_history
FROM point_histories point_history
JOIN users legacy_user ON legacy_user.id = point_history.user_id
WHERE legacy_user.email = 'test@test.com'
  AND '${removeLegacyTestUser}' = 'true';

DELETE prediction
FROM user_predictions prediction
JOIN users legacy_user ON legacy_user.id = prediction.user_id
WHERE legacy_user.email = 'test@test.com'
  AND '${removeLegacyTestUser}' = 'true';

DELETE FROM users
WHERE email = 'test@test.com'
  AND '${removeLegacyTestUser}' = 'true';
