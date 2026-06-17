require 'spec_helper'
require 'net/http'
require 'digest'
require 'json'
require 'securerandom'

feature 'Dispatcher' do

  let(:token)      { "test-executor-#{SecureRandom.hex(8)}" }
  let(:token_hash) { Digest::SHA256.hexdigest(token) }
  let(:project_id) { "dispatcher-#{SecureRandom.hex(4)}" }
  let(:commit_id)  { 'a' * 40 }

  before :each do
    database[:repositories].insert(
      id:      project_id,
      git_url: 'file:///dev/null',
      name:    'Dispatch Test'
    )
  end

  def executor_sync(tok, available_load: 4.0)
    uri = URI("#{http_base_url}/executor/sync")
    req = Net::HTTP::Post.new(uri)
    req['Authorization'] = "Bearer #{tok}"
    req['Content-Type']  = 'application/json'
    req['Accept']        = 'application/json'
    req.body             = { available_load: available_load }.to_json
    res = Net::HTTP.start(uri.hostname, uri.port) { |h| h.request(req) }
    body = JSON.parse(res.body) rescue res.body
    [res.code.to_i, body]
  end

  def seed_executor(traits: [], max_load: 4.0)
    traits_literal = "{#{traits.join(',')}}"
    database.fetch(
      "INSERT INTO executors (name, token_hash, token_part, max_load, traits)
       VALUES (?, ?, ?, ?, CAST(? AS text[]))
       RETURNING id",
      "test-executor-#{SecureRandom.hex(4)}",
      token_hash,
      token[0..7],
      max_load,
      traits_literal
    ).first
  end

  def seed_pending_trial(task_traits: [], load: 1.0)
    job_id = database[:jobs].insert(
      project_id: project_id,
      commit_id:  commit_id,
      key:        SecureRandom.hex(4),
      state:      'pending',
      name:       'test job'
    )
    traits_literal = "{#{task_traits.join(',')}}"
    task_id = database.fetch(
      "INSERT INTO tasks (job_id, name, state, traits, load)
       VALUES (?, 'test task', 'pending', CAST(? AS text[]), ?)
       RETURNING id",
      job_id, traits_literal, load
    ).first[:id]
    database[:trials].insert(task_id: task_id, state: 'pending')
    task_id
  end

  scenario 'dispatches trials whose task traits are a subset of executor traits' do
    seed_executor(traits: ['Bash', 'Ruby'])
    seed_pending_trial(task_traits: ['Bash'])
    seed_pending_trial(task_traits: ['Python'])

    status, body = executor_sync(token)

    expect(status).to eq 200
    expect(body['trials_to_execute'].length).to eq 1
    expect(database[:trials].where(state: 'dispatching').count).to eq 1
    expect(database[:trials].where(state: 'pending').count).to eq 1
  end

  scenario 'skips trials whose task load exceeds available_load' do
    seed_executor(traits: ['Bash'])
    seed_pending_trial(task_traits: ['Bash'], load: 5.0)

    status, body = executor_sync(token, available_load: 3.0)

    expect(status).to eq 200
    expect(body['trials_to_execute'].length).to eq 0
    expect(database[:trials].where(state: 'pending').count).to eq 1
  end

  scenario 'tasks with empty traits are dispatched to all executors' do
    seed_executor(traits: ['Bash'])
    seed_pending_trial(task_traits: [])

    status, body = executor_sync(token)

    expect(status).to eq 200
    expect(body['trials_to_execute'].length).to eq 1
    expect(database[:trials].where(state: 'dispatching').count).to eq 1
  end

  scenario 'updates last_seen_at on sync' do
    seed_executor(traits: [])

    executor_sync(token)

    executor = database[:executors].where(token_hash: token_hash).first
    expect(executor[:last_seen_at]).not_to be_nil
  end

  scenario 'returns 401 for invalid token' do
    seed_executor(traits: [])
    status, _body = executor_sync('not-a-valid-token')
    expect(status).to eq 401
  end

end
