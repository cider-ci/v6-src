require 'spec_helper'

feature 'Database extensions' do

  scenario 'pgcrypto extension is enabled' do
    result = database['SELECT 1 FROM pg_extension WHERE extname = ?', 'pgcrypto'].first
    expect(result).not_to be_nil,
      'pgcrypto extension is not enabled — run: CREATE EXTENSION pgcrypto; ' \
      'or ensure migration 00018_pgcrypto_up.sql has been applied'
  end

  scenario 'gen_salt and crypt functions work' do
    result = database["SELECT crypt('test-password', gen_salt('bf')) AS hash"].first
    expect(result[:hash]).to start_with '$2'
  end

end
