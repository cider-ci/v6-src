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

  def executor_sync(tok, available_load: 4.0, accepted_repositories: nil)
    uri = URI("#{http_base_url}/executor/sync")
    req = Net::HTTP::Post.new(uri)
    req['Authorization'] = "Bearer #{tok}"
    req['Content-Type']  = 'application/json'
    req['Accept']        = 'application/json'
    payload = { available_load: available_load }
    payload[:accepted_repositories] = accepted_repositories if accepted_repositories
    req.body             = payload.to_json
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

  context 'accepted repositories' do
    def stored_accepted_repositories
      JSON.parse(database.fetch('SELECT accepted_repositories::text AS a FROM executors').first[:a])
    end

    def dispatched_count(accepted_repositories)
      seed_executor
      seed_pending_trial
      _, body = executor_sync(token, accepted_repositories: accepted_repositories)
      body['trials_to_execute'].length
    end

    scenario 'an executor that does not report them accepts all repositories' do
      expect(dispatched_count(nil)).to eq 1
      expect(stored_accepted_repositories).to eq [{ 'url' => '^.*$' }]
    end

    scenario 'a trial of a repository that does not match is not dispatched' do
      expect(dispatched_count([{ url: '^https://github\\.com/.+$' }, { url: 'file:///other' }])).to eq 0
      expect(database[:trials].where(state: 'pending').count).to eq 1
    end

    scenario 'an exact url matches' do
      expect(dispatched_count([{ url: 'file:///dev/null' }])).to eq 1
    end

    scenario 'a ^...$ regex matches' do
      expect(dispatched_count([{ url: '^file:///dev/.+$' }])).to eq 1
    end

    scenario 'a plain string entry is a url pattern' do
      expect(dispatched_count(['file:///dev/null'])).to eq 1
      expect(stored_accepted_repositories).to eq [{ 'url' => 'file:///dev/null' }]
    end

    scenario 'an invalid regex does not break dispatching' do
      expect(dispatched_count([{ url: '^file:///(dev$' }, { url: 'file:///dev/null' }])).to eq 1
    end

    scenario 'an empty list accepts nothing' do
      expect(dispatched_count([])).to eq 0
    end

    scenario 'entries with commas, braces and quotes are stored unchanged' do
      entries = [{ 'url' => '^file:///d{1,3}ev/.+$' },
                 { 'url' => 'https://x/"quoted"', 'branches' => ['^a,b$'] }]
      dispatched_count(entries)
      expect(stored_accepted_repositories).to eq entries
    end

    context 'restricted to branches (the commit must be the head of a matching branch)' do
      let(:older_commit) { 'c' * 40 }
      let(:master_only) { [{ url: 'file:///dev/null', branches: ['^master$'] }] }

      before :each do
        [commit_id, older_commit].each do |id|
          database[:commits].insert(id: id, tree_id: 'b' * 40, subject: id[0], author_name: 'A', committer_name: 'A')
        end
      end

      def branch!(name, head)
        database[:branches].insert(repository_id: project_id, name: name, current_commit_id: head)
      end

      scenario 'the head of a matching branch is dispatched' do
        branch!('master', commit_id)
        expect(dispatched_count(master_only)).to eq 1
      end

      scenario 'the head of another branch only is not dispatched' do
        branch!('beta', commit_id)
        branch!('master', older_commit)
        expect(dispatched_count(master_only)).to eq 0
      end

      scenario 'a commit that a matching branch has moved past is not dispatched' do
        branch!('master', older_commit)
        database[:branches_commits].insert(branch_id: database[:branches].first[:id], commit_id: commit_id)
        expect(dispatched_count(master_only)).to eq 0
      end

      scenario 'a branch list and an exact branch name work, as does a second entry without branches' do
        branch!('release/1.0', commit_id)
        expect(dispatched_count([{ url: 'file:///dev/null', branches: ['^master$', '^release/.+$'] }])).to eq 1
        database[:trials].update(state: 'pending', executor_id: nil)
        _, body = executor_sync(token, accepted_repositories: [{ url: 'file:///dev/null', branches: 'release/1.0' }])
        expect(body['trials_to_execute'].length).to eq 1
        database[:trials].update(state: 'pending', executor_id: nil)
        _, body = executor_sync(token, accepted_repositories: [{ url: 'file:///dev/null', branches: ['^master$'] },
                                                              { url: '^file:.+$' }])
        expect(body['trials_to_execute'].length).to eq 1
      end
    end
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

  scenario 'updates executor traits on sync' do
    seed_executor(traits: [])

    uri = URI("#{http_base_url}/executor/sync")
    req = Net::HTTP::Post.new(uri)
    req['Authorization'] = "Bearer #{token}"
    req['Content-Type']  = 'application/json'
    req['Accept']        = 'application/json'
    req.body             = { available_load: 4.0, traits: ['Ruby', 'Bash'] }.to_json
    Net::HTTP.start(uri.hostname, uri.port) { |h| h.request(req) }

    executor = database[:executors].where(token_hash: token_hash).first
    expect(executor[:traits]).to include('ruby', 'bash')
  end

  scenario 'dispatch_storm_delay_seconds blocks re-dispatch within the delay window' do
    seed_executor(traits: [])

    # A task with a 60-second re-dispatch delay
    job_id = database[:jobs].insert(
      project_id: project_id, commit_id: commit_id,
      key: SecureRandom.hex(4), state: 'pending', name: 'storm delay job'
    )
    task_id = database.fetch(
      "INSERT INTO tasks (job_id, name, state, traits, load, spec)
       VALUES (?, 'storm task', 'pending', '{}', 1.0, '{\"dispatch_storm_delay_seconds\": 60}'::jsonb)
       RETURNING id",
      job_id
    ).first[:id]
    # Existing trial dispatched just now (within the delay window)
    database[:trials].insert(task_id: task_id, state: 'dispatching',
                              dispatched_at: Time.now.utc)
    # New pending trial for the same task — should be held back
    database[:trials].insert(task_id: task_id, state: 'pending')

    status, body = executor_sync(token)

    expect(status).to eq 200
    expect(body['trials_to_execute']).to be_empty
  end

  scenario 'dispatch_storm_delay_seconds allows dispatch after the delay has elapsed' do
    seed_executor(traits: [])

    job_id = database[:jobs].insert(
      project_id: project_id, commit_id: commit_id,
      key: SecureRandom.hex(4), state: 'pending', name: 'storm delay elapsed job'
    )
    task_id = database.fetch(
      "INSERT INTO tasks (job_id, name, state, traits, load, spec)
       VALUES (?, 'storm task', 'pending', '{}', 1.0, '{\"dispatch_storm_delay_seconds\": 60}'::jsonb)
       RETURNING id",
      job_id
    ).first[:id]
    # Existing trial dispatched 2 minutes ago (outside the delay window)
    database[:trials].insert(task_id: task_id, state: 'dispatching',
                              dispatched_at: Time.now.utc - 120)
    database[:trials].insert(task_id: task_id, state: 'pending')

    status, body = executor_sync(token)

    expect(status).to eq 200
    expect(body['trials_to_execute'].length).to eq 1
  end

end
