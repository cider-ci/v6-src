require 'spec_helper'
require 'timeout'
require 'securerandom'
require 'tmpdir'

# A job without tasks (e.g. a generate_tasks that matches no file, madek
# api-v2 clojure-tests) is set to passed right away (legacy
# check-tasks-empty!) instead of staying pending forever and blocking the
# jobs that depend on it. The job page explains it.
feature 'Jobs without tasks' do

  let(:project_id) { "empty-job-#{SecureRandom.hex(4)}" }

  EMPTY_JOB_CIDER_CI_YML = <<~YML
    jobs:
      empty-gen:
        name: Generates no tasks
        run_when:
          any branch:
            type: branch
            include_match: ^.*$
        context:
          generate_tasks:
            include_match: test/.*_test.clj
          task_defaults:
            scripts:
              test:
                body: 'true'
      after-empty:
        name: Depends on the empty job
        depends_on: &DEP
          empty passed: { type: job, job_key: empty-gen, states: [passed] }
        run_when: *DEP
        task: 'true'
      empty-manual:
        name: Manual without tasks
        context:
          tasks: {}
  YML

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    @repo_dir = Dir.mktmpdir('cider-ci-empty-job-repo')
    File.write(File.join(@repo_dir, 'cider-ci.yml'), EMPTY_JOB_CIDER_CI_YML)
    git = ->(*args) { system('git', '-C', @repo_dir, *args, out: File::NULL, err: File::NULL) or raise "git #{args.join(' ')} failed" }
    git.call('init', '-q', '-b', 'master')
    git.call('-c', 'user.name=spec', '-c', 'user.email=spec@example.com', '-c', 'commit.gpgsign=false',
             'add', 'cider-ci.yml')
    git.call('-c', 'user.name=spec', '-c', 'user.email=spec@example.com', '-c', 'commit.gpgsign=false',
             'commit', '-q', '-m', 'empty job fixture')
    @commit_id = `git -C #{@repo_dir} rev-parse HEAD`.strip

    database[:repositories].insert(id: project_id, name: 'Empty Job Fixture', git_url: "file://#{@repo_dir}",
                                   branch_trigger_max_commit_age: nil)
    reload_server_repository_state
    sleep 1

    visit "/projects/#{project_id}"
    find('button', text: /[Ff]etch/).click
    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count > 0 }
  end

  after :each do
    FileUtils.rm_rf(@repo_dir) if @repo_dir
  end

  def job_row(key)
    database[:jobs].where(project_id: project_id, key: key).first
  end

  scenario 'an auto-triggered job without tasks passes and its dependents get triggered' do
    Timeout.timeout(30) { sleep 1 until job_row('empty-gen') }
    expect(job_row('empty-gen')[:state]).to eq 'passed'
    expect(database[:tasks].where(job_id: job_row('empty-gen')[:id]).count).to eq 0

    Timeout.timeout(60) { sleep 1 until job_row('after-empty') }

    visit "/projects/#{project_id}/commits/#{@commit_id}/jobs/#{job_row('empty-gen')[:id]}"
    expect(page).to have_css('.alert.no-tasks', text: 'No tasks have been created')
  end

  scenario 'a manually triggered job without tasks passes' do
    visit "/projects/#{project_id}/commits/#{@commit_id}/jobs"
    find('tr', text: 'Manual without tasks').find('button', text: 'Run').click
    Timeout.timeout(20) { sleep 0.5 until job_row('empty-manual') }
    expect(job_row('empty-manual')[:state]).to eq 'passed'
  end
end
