require 'spec_helper'
require 'timeout'
require 'securerandom'
require 'tmpdir'

# Legacy trigger semantics: a job is only started automatically by one of its
# run_when entries, and only once its depends_on are fulfilled; depends_on
# alone never triggers. madek's deploy jobs depend on the preflight check but
# have no run_when (manual only); v6 used to create all of them as soon as the
# preflight check had passed, on every branch.
feature 'Dependency triggers' do

  let(:project_id) { "dep-trigger-#{SecureRandom.hex(4)}" }

  DEP_TRIGGER_CIDER_CI_YML = <<~YML
    jobs:
      pre:
        name: Prerequisite without tasks (passes right away)
        run_when:
          any branch: { type: branch, include_match: ^.*$ }
        context:
          generate_tasks:
            include_match: test/.*_test.clj
          task_defaults:
            scripts:
              test:
                body: 'true'
      manual-only:
        name: Depends on pre, no run_when
        depends_on:
          pre passed: { type: job, job_key: pre, states: [passed] }
        task: 'true'
      on-job:
        name: Depends on pre, run_when job
        depends_on: &PRE
          pre passed: { type: job, job_key: pre, states: [passed] }
        run_when: *PRE
        task: 'true'
      on-master:
        name: Depends on pre, run_when branch master
        depends_on: *PRE
        run_when:
          master: { type: branch, include_match: ^master$ }
        task: 'true'
      on-release:
        name: Depends on pre, run_when branch release
        depends_on: *PRE
        run_when:
          release: { type: branch, include_match: ^release$ }
        task: 'true'
  YML

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    @repo_dir = Dir.mktmpdir('cider-ci-dep-trigger-repo')
    File.write(File.join(@repo_dir, 'cider-ci.yml'), DEP_TRIGGER_CIDER_CI_YML)
    git = ->(*args) { system('git', '-C', @repo_dir, *args, out: File::NULL, err: File::NULL) or raise "git #{args.join(' ')} failed" }
    git.call('init', '-q', '-b', 'master')
    git.call('-c', 'user.name=spec', '-c', 'user.email=spec@example.com', '-c', 'commit.gpgsign=false',
             'add', 'cider-ci.yml')
    git.call('-c', 'user.name=spec', '-c', 'user.email=spec@example.com', '-c', 'commit.gpgsign=false',
             'commit', '-q', '-m', 'dependency trigger fixture')

    database[:repositories].insert(id: project_id, name: 'Dependency Trigger Fixture',
                                   git_url: "file://#{@repo_dir}", branch_trigger_max_commit_age: nil)
    reload_server_repository_state
    sleep 1

    visit "/projects/#{project_id}"
    find('button', text: /[Ff]etch/).click
    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count > 0 }
  end

  after :each do
    FileUtils.rm_rf(@repo_dir) if @repo_dir
  end

  def job_keys
    database[:jobs].where(project_id: project_id).select_map(:key).sort
  end

  scenario 'only run_when entries trigger; depends_on alone does not' do
    Timeout.timeout(60) { sleep 1 until (%w[on-job on-master] - job_keys).empty? }
    sleep 25 # give the dep-trigger daemon (every 10 s) a couple more cycles

    expect(job_keys).to eq %w[on-job on-master pre]
  end
end
