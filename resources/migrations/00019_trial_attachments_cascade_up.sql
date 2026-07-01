ALTER TABLE trial_attachments
  DROP CONSTRAINT trial_attachments_trial_id_fkey,
  ADD CONSTRAINT trial_attachments_trial_id_fkey
    FOREIGN KEY (trial_id) REFERENCES trials(id) ON DELETE CASCADE;
