require 'spec_helper'

feature 'Trial PATCH error handling' do
  include_context 'with executor api'

  scenario 'PATCH to non-existent trial returns 404' do
    fake_uuid = '00000000-0000-0000-0000-000000000000'
    code, _body = executor_api(:patch, "/executor/trials/#{fake_uuid}", {state: 'passed'})
    expect(code).to eq 404
  end

  scenario 'PATCH without state field returns 400' do
    job_id   = seed_job
    task_id  = seed_task(job_id)
    trial_id = seed_trial(task_id)

    code, _body = executor_api(:patch, "/executor/trials/#{trial_id}", {error: 'oops'})
    expect(code).to eq 400
  end

  scenario 'sync with invalid token returns 401' do
    code, _body = executor_api(:post, '/executor/sync', {available_load: 1.0})
    # Use a different token for this call
    uri = URI("#{http_base_url}/executor/sync")
    req = Net::HTTP::Post.new(uri)
    req['Content-Type']  = 'application/json'
    req['Accept']        = 'application/json'
    req['Authorization'] = 'Bearer invalid-token-xyz'
    req.body             = {available_load: 1.0}.to_json
    res = Net::HTTP.start(uri.host, uri.port) { |h| h.request(req) }
    expect(res.code.to_i).to eq 401
  end

  scenario 'disabled executor is rejected with 401' do
    disabled_token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       'disabled-executor',
      token_hash: Digest::SHA256.hexdigest(disabled_token),
      token_part: disabled_token[0, 8],
      enabled:    false
    )

    uri = URI("#{http_base_url}/executor/sync")
    req = Net::HTTP::Post.new(uri)
    req['Content-Type']  = 'application/json'
    req['Accept']        = 'application/json'
    req['Authorization'] = "Bearer #{disabled_token}"
    req.body             = {available_load: 1.0}.to_json
    res = Net::HTTP.start(uri.host, uri.port) { |h| h.request(req) }
    expect(res.code.to_i).to eq 401
  end
end
