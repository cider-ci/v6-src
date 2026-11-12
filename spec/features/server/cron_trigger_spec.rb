require 'spec_helper'
require 'timeout'
require 'securerandom'
require 'tmpdir'

# run_when entries of type cron (legacy cron trigger): the cron-trigger daemon
# (every 30 s) creates the job for the head of every matching branch of a
# repository with cron_trigger_enabled once the expression fired within the
# last 3 minutes. With `rerun: true` a finished job older than 3 minutes is
# deleted and created anew (nightly staging/test resets of madek and leihs).
feature 'Cron trigger' do

  let(:project_id) { "cron-#{SecureRandom.hex(4)}" }

  CRON_JOBS_YML = <<~YML
    jobs:
      cron-plain:
        name: Cron job
        run_when:
          every minute:
            type: cron
            value: '* * * * *'
            branch_include_match: ^cron-test$
        task: 'true'
      cron-rerun:
        name: Cron job with rerun
        run_when:
          every minute:
            type: cron
            value: '* * * * *'
            branch_include_match: ^cron-test$
            rerun: true
        task: 'true'
      cron-dependent:
        name: Cron job depending on cron-plain
        depends_on:
          plain passed:
            type: job
            job_key: cron-plain
            states: [passed]
        run_when:
          every minute:
            type: cron
            value: '* * * * *'
            branch_include_match: ^cron-test$
        task: 'true'
      cron-master-only:
        name: Cron job for another branch
        run_when:
          every minute:
            type: cron
            value: '* * * * *'
            branch_include_match: ^master$
        task: 'true'
      cron-no-branch:
        name: Cron job without branch_include_match (never fires)
        run_when:
          every minute:
            type: cron
            value: '* * * * *'
        task: 'true'
  YML

  def git(dir, *args)
    out = IO.popen(['git', '-C', dir, '-c', 'user.name=spec', '-c', 'user.email=spec@example.com',
                    '-c', 'commit.gpgsign=false', '-c', 'protocol.file.allow=always', *args],
                   err: [:child, :out]) { |io| io.read }
    raise "git #{args.join(' ')} failed:\n#{out}" unless $?.success?
    out
  end

  def jobs(key = nil)
    ds = database[:jobs].where(project_id: project_id)
    key ? ds.where(key: key) : ds
  end

  def setup_project(cron_trigger_enabled)
    database[:repositories].insert(id: project_id, name: 'Cron Test', git_url: "file://#{@remote}",
                                   branch_trigger_max_commit_age: nil,
                                   cron_trigger_enabled: cron_trigger_enabled)
    reload_server_repository_state
    sleep 1
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click
    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= 1 }
  end

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    @root = Dir.mktmpdir('cider-ci-cron')
    work = File.join(@root, 'work')
    @remote = File.join(@root, 'remote.git')
    Dir.mkdir(work)
    git(work, 'init', '-q', '-b', 'cron-test')
    File.write(File.join(work, 'cider-ci.yml'), CRON_JOBS_YML)
    git(work, 'add', '.')
    git(work, 'commit', '-q', '-m', 'cron jobs')
    git(work, 'clone', '-q', '--bare', work, @remote)
  end

  after :each do
    FileUtils.rm_rf(@root) if @root
  end

  scenario 'jobs with a matching cron entry are created, rerun replaces finished jobs' do
    setup_project(true)

    # the daemon runs every 30 s, '* * * * *' always fires
    Timeout.timeout(75) { sleep 1 until jobs('cron-plain').count == 1 && jobs('cron-rerun').count == 1 }
    plain_id = jobs('cron-plain').first[:id]
    rerun_id = jobs('cron-rerun').first[:id]

    sleep 35  # one more cycle: nothing may be created twice or wrongly
    expect(jobs('cron-plain').count).to eq 1
    expect(jobs('cron-rerun').count).to eq 1
    expect(jobs('cron-rerun').first[:id]).to eq rerun_id
    expect(jobs('cron-master-only').count).to eq 0  # no such branch
    expect(jobs('cron-no-branch').count).to eq 0    # no branch_include_match
    expect(jobs('cron-dependent').count).to eq 0    # cron-plain not passed yet

    # both jobs finished more than 3 minutes ago
    jobs.where(key: %w[cron-plain cron-rerun]).update(state: 'passed', updated_at: Time.now - 4 * 60)

    # the rerun job is replaced by a new one, the plain one stays; the
    # dependent job is created now that cron-plain passed
    Timeout.timeout(75) { sleep 1 until jobs('cron-rerun').first[:id] != rerun_id }
    expect(jobs('cron-rerun').count).to eq 1
    expect(jobs('cron-rerun').first[:state]).to eq 'pending'
    expect(database[:jobs].where(id: rerun_id).count).to eq 0
    Timeout.timeout(75) { sleep 1 until jobs('cron-dependent').count == 1 }
    expect(jobs('cron-plain').count).to eq 1
    expect(jobs('cron-plain').first[:id]).to eq plain_id

    # the jobs page describes the cron entries
    visit "/projects/#{project_id}/commits/#{database[:branches].where(repository_id: project_id).first[:current_commit_id]}/jobs"
    expect(page).to have_content 'every minute'
  end

  scenario 'nothing is created unless the repository has cron triggers enabled' do
    setup_project(false)
    sleep 40
    expect(jobs.count).to eq 0

    # enabling it in the project form starts the triggers
    visit "/projects/#{project_id}/edit"
    find('#cron_trigger_enabled').click
    click_on 'Save'
    expect(page).to have_content 'Cron triggers'
    expect(page).to have_content 'enabled'
    Timeout.timeout(75) { sleep 1 until jobs('cron-plain').count == 1 }
  end

end
