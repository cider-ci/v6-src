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

  # Disabled means "no new trials", not "unknown": the executor keeps syncing
  # (so the admin page shows it online with its real load) and may finish or
  # abort what it already runs.
  scenario 'disabled executor syncs normally but gets no trials' do
    job_id   = seed_job
    task_id  = seed_task(job_id, state: 'pending')
    trial_id = seed_trial(task_id, state: 'pending')
    database[:executors].where(token_part: @executor_token[0, 8]).update(enabled: false, last_seen_at: nil)

    code, body = executor_api(:post, '/executor/sync', {available_load: 4.0, max_load: 7, traits: ['foo']})
    expect(code).to eq 200
    expect(body['trials_to_execute']).to eq []
    expect(database[:trials].where(id: trial_id).first[:state]).to eq 'pending'

    executor = database[:executors].where(token_part: @executor_token[0, 8]).first
    expect(executor[:last_seen_at]).not_to be_nil
    expect(executor[:max_load].to_f).to eq 7.0
    expect(executor[:traits]).to include 'foo'

    # finishing a running trial still works while disabled
    running_trial = seed_trial(task_id, state: 'executing')
    code, _body = executor_api(:patch, "/executor/trials/#{running_trial}", {state: 'passed'})
    expect(code).to eq 200

    # re-enabled: the pending trial is dispatched at the next sync
    database[:executors].where(token_part: @executor_token[0, 8]).update(enabled: true)
    _code, body = executor_api(:post, '/executor/sync', {available_load: 4.0})
    expect(body['trials_to_execute'].map { |t| t['id'] }).to eq [trial_id.to_s]
  end
end
