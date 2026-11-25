require 'spec_helper'
require 'timeout'
require 'securerandom'
require 'tmpdir'

# The schedule page: the next cron firings over all projects, soonest first,
# with what each firing will do (create the job, replace it with rerun,
# nothing because it exists, or wait for dependencies).
feature 'Scheduled cron firings' do

  let(:project_id) { "sched-#{SecureRandom.hex(4)}" }

  SCHEDULED_JOBS_YML = <<~YML
    jobs:
      nightly:
        name: Nightly job
        run_when:
          nightly:
            type: cron
            value: '30 4 * * *'
            branch_include_match: ^sched-test$
        task: 'true'
      weekly-rerun:
        name: Weekly rerun job
        run_when:
          sunday:
            type: cron
            value: '0 6 * * 0'
            branch_include_match: ^sched-test$
            rerun: true
        task: 'true'
      dependent:
        name: Dependent job
        depends_on:
          nightly passed:
            type: job
            job_key: nightly
            states: [passed]
        run_when:
          hourly:
            type: cron
            value: '0 * * * *'
            branch_include_match: ^sched-test$
        task: 'true'
      other-branch:
        name: Other branch job
        run_when:
          hourly:
            type: cron
            value: '15 * * * *'
            branch_include_match: ^master$
        task: 'true'
  YML

  def git(dir, *args)
    out = IO.popen(['git', '-C', dir, '-c', 'user.name=spec', '-c', 'user.email=spec@example.com',
                    '-c', 'commit.gpgsign=false', '-c', 'protocol.file.allow=always', *args],
                   err: [:child, :out]) { |io| io.read }
    raise "git #{args.join(' ')} failed:\n#{out}" unless $?.success?
    out
  end

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    @root = Dir.mktmpdir('cider-ci-sched')
    work = File.join(@root, 'work')
    @remote = File.join(@root, 'remote.git')
    Dir.mkdir(work)
    git(work, 'init', '-q', '-b', 'sched-test')
    File.write(File.join(work, 'cider-ci.yml'), SCHEDULED_JOBS_YML)
    git(work, 'add', '.')
    git(work, 'commit', '-q', '-m', 'cron jobs')
    git(work, 'clone', '-q', '--bare', work, @remote)
    # no branch trigger override: the repository's branch filter applies to
    # cron firings too (none of these jobs has a branch run_when anyway)
    database[:repositories].insert(id: project_id, name: 'Sched Test', git_url: "file://#{@remote}",
                                   branch_trigger_max_commit_age: nil, cron_trigger_enabled: true)
    reload_server_repository_state
    sleep 1
    visit "/projects/#{project_id}"
    find('button', text: /Fetch/).click
    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count >= 1 }
  end

  after :each do
    FileUtils.rm_rf(@root) if @root
  end

  scenario 'lists the next firings soonest first with their effect; the limit is selectable' do
    visit '/scheduled/'
    expect(page).to have_css 'tr.scheduled-row', text: 'Nightly job'
    rows = all('tr.scheduled-row').map(&:text)
    mine = rows.select { |r| r.include?(project_id) }
    expect(mine.map { |r| r[/(Nightly job|Weekly rerun job|Dependent job|Other branch job)/, 1] })
      .to eq ['Dependent job', 'Nightly job', 'Weekly rerun job'].sort_by { |n| mine.index { |r| r.include?(n) } }
    expect(mine).not_to include(a_string_matching(/Other branch job/))   # branch filter

    # hourly before nightly before the weekly one (next firings are within a
    # day, a day, a week respectively), unless the clock is just before one
    nightly = find('tr.scheduled-row', text: 'Nightly job')
    expect(nightly).to have_content '30 4 * * *'
    expect(nightly).to have_css '.effect', text: 'creates the job'
    expect(find('tr.scheduled-row', text: 'Weekly rerun job')).to have_css '.badge', text: 'rerun'
    expect(find('tr.scheduled-row', text: 'Dependent job')).to have_css '.effect', text: 'waits for nightly passed'

    # an existing job: the firing does nothing, with rerun it is replaced
    commit = database[:branches].where(repository_id: project_id).first[:current_commit_id]
    database[:jobs].insert(project_id: project_id, commit_id: commit, key: 'nightly', name: 'Nightly job', state: 'passed')
    database[:jobs].insert(project_id: project_id, commit_id: commit, key: 'weekly-rerun', name: 'Weekly rerun job', state: 'failed')
    visit '/scheduled/?limit=200'
    expect(find('tr.scheduled-row', text: 'Nightly job')).to have_css '.effect', text: 'nothing: job exists (passed)'
    expect(find('tr.scheduled-row', text: 'Weekly rerun job')).to have_css '.effect', text: 'replaces the failed job'
    expect(find('tr.scheduled-row', text: 'Dependent job')).to have_css '.effect', text: 'creates the job'
    expect(find('select.scheduled-limit').value).to eq '200'
  end

  scenario 'the JSON lists the same rows' do
    json = page.evaluate_async_script(<<~JS)
      const done = arguments[0];
      fetch('/scheduled/?limit=5', {headers: {accept: 'application/json'}}).then(r => r.json()).then(done);
    JS
    expect(json['limit']).to eq 5
    expect(json['rows'].size).to be <= 5
    expect(json['rows'].map { |r| r['project_id'] }).to include project_id
    expect(json['rows'].first.keys).to include('next_at', 'cron', 'job_key', 'branch_name', 'commit_id')
  end
end
