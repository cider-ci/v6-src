require 'net/http'
require 'json'
require 'digest'
require 'securerandom'

# Shared context for tests that exercise the executor HTTP API directly
# (no real executor subprocess needed — just HTTP calls against the server).
shared_context 'with executor api' do
  let(:project_id) { "test-#{SecureRandom.hex(4)}" }
  let(:commit_id)  { 'a' * 40 }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    @executor_token = SecureRandom.hex(32)
    database[:executors].insert(
      name:       "api-executor-#{SecureRandom.hex(4)}",
      token_hash: Digest::SHA256.hexdigest(@executor_token),
      token_part: @executor_token[0, 8],
      enabled:    true
    )
    database[:repositories].insert(id: project_id, name: 'Test', git_url: 'local')
  end

  def executor_api(method, path, body = nil)
    uri = URI("#{http_base_url}#{path}")
    req = case method
          when :post  then Net::HTTP::Post.new(uri)
          when :patch then Net::HTTP::Patch.new(uri)
          when :get   then Net::HTTP::Get.new(uri)
          end
    req['Content-Type']  = 'application/json'
    req['Accept']        = 'application/json'
    req['Authorization'] = "Bearer #{@executor_token}"
    req.body = body.to_json if body
    res = Net::HTTP.start(uri.host, uri.port) { |h| h.request(req) }
    [res.code.to_i, (JSON.parse(res.body) rescue res.body)]
  end

  def seed_job(state: 'pending')
    database[:jobs].insert(
      project_id: project_id,
      commit_id:  commit_id,
      key:        SecureRandom.hex(4),
      state:      state,
      name:       'test job'
    )
  end

  def seed_task(job_id, spec: {}, state: 'pending', traits: [])
    traits_literal = "{#{traits.join(',')}}"
    database.fetch(
      "INSERT INTO tasks (job_id, name, state, traits, load, spec)
       VALUES (?, 'main', ?, CAST(? AS text[]), 1.0, CAST(? AS jsonb))
       RETURNING id",
      job_id, state, traits_literal, spec.to_json
    ).first[:id]
  end

  def seed_trial(task_id, state: 'dispatching')
    database[:trials].insert(task_id: task_id, state: state)
  end

  def patch_trial(trial_id, state, extra = {})
    _code, body = executor_api(:patch, "/executor/trials/#{trial_id}", {state: state}.merge(extra))
    body
  end
end
