require 'spec_helper'
require 'timeout'
require 'securerandom'
require 'tmpdir'

# Legacy parity: when a push makes a commit unreachable from every branch head
# (force push, branch deleted), the jobs running or waiting on that commit are
# aborted: pending trials aborted, running ones aborting (the executor kills
# them), no retries. Jobs started by hand on a superseded commit afterwards
# are left alone, and a commit still on another branch is not touched.
feature 'Revocation of jobs on unreachable commits' do

  let(:project_id) { "revoke-#{SecureRandom.hex(4)}" }

  def git(dir, *args)
    out = IO.popen(['git', '-C', dir, '-c', 'user.name=spec', '-c', 'user.email=spec@example.com',
                    '-c', 'commit.gpgsign=false', '-c', 'protocol.file.allow=always', *args],
                   err: [:child, :out]) { |io| io.read }
    raise "git #{args.join(' ')} failed:\n#{out}" unless $?.success?
    out
  end

  def commit!(msg)
    File.write(File.join(@work, msg.tr(' ', '-')), msg)
    git(@work, 'add', '.')
    git(@work, 'commit', '-q', '-m', msg)
    git(@work, 'rev-parse', 'HEAD').strip
  end

  def head_of(branch)
    database[:branches].where(repository_id: project_id, name: branch).get(:current_commit_id)
  end

  def fetch!(branch, expected_head)
    git(@work, 'push', '-q', '--force', '--all', @remote)
    visit "/projects/#{project_id}"
    find('button', text: /[Ff]etch/).click
    Timeout.timeout(30) { sleep 0.5 until head_of(branch) == expected_head }
  end

  def seed_job(commit_id, key)
    job_id  = database[:jobs].insert(project_id: project_id, commit_id: commit_id, key: key, name: key, state: 'executing')
    task_id = database.fetch("INSERT INTO tasks (job_id, name, state, traits, load, spec)
                              VALUES (?, 'main', 'executing', '{}', 1.0, '{}'::jsonb) RETURNING id", job_id).first[:id]
    pending   = database[:trials].insert(task_id: task_id, state: 'pending')
    executing = database[:trials].insert(task_id: task_id, state: 'executing', dispatched_at: Time.now.utc,
                                         started_at: Time.now.utc)
    { job: job_id, pending: pending, executing: executing }
  end

  def states(h)
    { job: database[:jobs].where(id: h[:job]).get(:state),
      pending: database[:trials].where(id: h[:pending]).get(:state),
      executing: database[:trials].where(id: h[:executing]).get(:state) }
  end

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    @root   = Dir.mktmpdir('cider-ci-revoke')
    @work   = File.join(@root, 'work')
    @remote = File.join(@root, 'remote.git')
    Dir.mkdir(@work)
    git(@work, 'init', '-q', '-b', 'master')
    @c0 = commit!('zero')
    @c1 = commit!('one')
    git(@work, 'branch', 'keep')          # keep points at c1 too
    git(@work, 'checkout', '-q', '-b', 'feature')
    @f1 = commit!('feature one')
    git(@work, 'checkout', '-q', 'master')
    git(@work, 'init', '-q', '--bare', @remote)
    database[:repositories].insert(id: project_id, name: 'Revoke', git_url: "file://#{@remote}",
                                   branch_trigger_include_match: '^__none__$')
    reload_server_repository_state
    sleep 1
    fetch!('feature', @f1)
  end

  after :each do
    FileUtils.rm_rf(@root) if @root
  end

  scenario 'a force push aborts the jobs of the commits it cuts off, not those still on another branch' do
    on_feature = seed_job(@f1, 'on-feature')     # feature's head: cut off below
    on_c1      = seed_job(@c1, 'on-c1')          # master's head, but keep still points at it

    # rewrite feature and master: f1 and c1 are no longer their heads
    git(@work, 'checkout', '-q', 'feature')
    git(@work, 'reset', '-q', '--hard', @c0)
    f2 = commit!('feature two')
    git(@work, 'checkout', '-q', 'master')
    git(@work, 'reset', '-q', '--hard', @c0)
    c2 = commit!('master two')
    fetch!('feature', f2)
    Timeout.timeout(10) { sleep 0.5 until head_of('master') == c2 }

    expect(states(on_feature)).to eq(job: 'aborting', pending: 'aborted', executing: 'aborting')
    expect(database[:jobs].where(id: on_feature[:job]).get(:abort_reason)).to match(/no longer reachable/)
    expect(states(on_c1)).to eq(job: 'executing', pending: 'pending', executing: 'executing')   # keep reaches c1

    visit "/projects/#{project_id}/commits/#{@f1}/jobs/#{on_feature[:job]}"
    expect(page).to have_css '.abort-reason', text: 'no longer reachable from any branch head'

    # no retry: the task has exactly the two trials it had
    expect(database[:trials].where(task_id: database[:trials].where(id: on_feature[:pending]).get(:task_id)).count).to eq 2
  end

  scenario 'a job started afterwards on a superseded commit survives later pushes' do
    git(@work, 'checkout', '-q', 'feature')
    git(@work, 'reset', '-q', '--hard', @c0)
    f2 = commit!('feature two')
    fetch!('feature', f2)
    expect(database[:branches_commits].where(commit_id: @f1).count).to eq 0

    later = seed_job(@f1, 'by-hand')      # started by hand on the cut-off commit
    f3 = commit!('feature three')
    fetch!('feature', f3)
    expect(states(later)).to eq(job: 'executing', pending: 'pending', executing: 'executing')
  end
end
