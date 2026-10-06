require 'spec_helper'
require 'timeout'
require 'securerandom'
require 'tmpdir'

# A project commit may point to a submodule commit that is pushed only later
# (or never). The branch update then cannot evaluate the job configuration
# (the include from the submodule does not resolve). Such updates are recorded
# in pending_branch_triggers, shown on the project page and retried by the
# branch-trigger-retry daemon; once the submodule's repository has the commit
# the jobs a push would have triggered are created. The repository's
# branch_trigger_max_commit_age still applies.
feature 'Pending branch triggers (submodule pushed later)' do

  let(:suffix)        { SecureRandom.hex(4) }
  let(:sub_project)   { "pbt-sub-#{suffix}" }
  let(:super_project) { "pbt-super-#{suffix}" }

  SUB_JOBS_YML = <<~YML
    jobs:
      from-submodule:
        name: Job defined in the submodule
        run_when:
          any branch:
            type: branch
            include_match: ^.*$
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

    @root = Dir.mktmpdir('cider-ci-pbt')
    sub_work   = File.join(@root, 'sub')
    @sub_remote = File.join(@root, 'sub-remote.git')
    super_work = File.join(@root, 'super')
    @super_remote = File.join(@root, 'super-remote.git')

    # submodule: commit A is pushed to its remote, commit B is not (yet)
    Dir.mkdir(sub_work)
    git(sub_work, 'init', '-q', '-b', 'master')
    FileUtils.mkdir_p(File.join(sub_work, 'cider-ci'))
    File.write(File.join(sub_work, 'cider-ci/jobs.yml'), SUB_JOBS_YML)
    git(sub_work, 'add', '.')
    git(sub_work, 'commit', '-q', '-m', 'A: jobs')
    git(sub_work, 'clone', '-q', '--bare', sub_work, @sub_remote)
    File.write(File.join(sub_work, 'cider-ci/jobs.yml'), SUB_JOBS_YML.sub('Job defined in the submodule', 'Job from submodule commit B'))
    git(sub_work, 'commit', '-q', '-am', 'B: rename job')
    @sub_commit_b = git(sub_work, 'rev-parse', 'HEAD').strip

    # super project: gitlink -> B, cider-ci.yml includes the jobs from the submodule
    Dir.mkdir(super_work)
    git(super_work, 'init', '-q', '-b', 'master')
    git(super_work, 'submodule', 'add', '-q', "file://#{@sub_remote}", 'sub')
    git(File.join(super_work, 'sub'), 'fetch', '-q', sub_work, 'master')
    git(File.join(super_work, 'sub'), 'checkout', '-q', @sub_commit_b)
    File.write(File.join(super_work, 'cider-ci.yml'), "include:\n  - path: cider-ci/jobs.yml\n    submodule: [sub]\n")
    git(super_work, 'add', '.')
    git(super_work, 'commit', '-q', '-m', 'super: include jobs from submodule at B')
    git(super_work, 'clone', '-q', '--bare', super_work, @super_remote)
    @super_commit = git(super_work, 'rev-parse', 'HEAD').strip

    database[:repositories].insert(id: sub_project, name: 'PBT Sub', git_url: "file://#{@sub_remote}",
                                   branch_trigger_include_match: '^__none__$')
    database[:repositories].insert(id: super_project, name: 'PBT Super', git_url: "file://#{@super_remote}",
                                   branch_trigger_max_commit_age: nil)
    reload_server_repository_state
    sleep 1
  end

  after :each do
    FileUtils.rm_rf(@root) if @root
  end

  def fetch_project!(project_id)
    visit "/projects/#{project_id}"
    find('button', text: /[Ff]etch/).click
    Timeout.timeout(30) { sleep 1 until database[:branches].where(repository_id: project_id).count > 0 }
  end

  def pending
    database[:pending_branch_triggers].where(repository_id: super_project).all
  end

  def super_jobs
    database[:jobs].where(project_id: super_project, commit_id: @super_commit)
  end

  scenario 'the branch update is recorded as pending and resolved once the submodule commit is pushed' do
    fetch_project!(sub_project)   # the server knows the submodule repository (with A only)
    fetch_project!(super_project)

    # the include cannot be resolved: no job, but a pending branch trigger
    Timeout.timeout(30) { sleep 1 until pending.any? }
    expect(super_jobs.count).to eq 0
    expect(pending.first[:branch_name]).to eq 'master'
    expect(pending.first[:commit_id]).to eq @super_commit
    expect(pending.first[:error]).to include('sub')

    visit "/projects/#{super_project}"
    expect(page).to have_content 'Unresolved Branch Updates'
    within('table.pending-branch-triggers') do
      expect(page).to have_content 'master'
      expect(page).to have_content @super_commit[0, 8]
    end

    # the daemon keeps retrying (and the entry stays) while B is missing
    Timeout.timeout(60) { sleep 1 until pending.first[:attempts] >= 2 }
    expect(super_jobs.count).to eq 0

    # now the submodule commit gets pushed and the submodule's repository fetched
    git(File.join(@root, 'sub'), 'push', '-q', @sub_remote, 'master')
    fetch_project!(sub_project)

    Timeout.timeout(90) { sleep 1 until super_jobs.count == 1 }
    expect(super_jobs.first[:key]).to eq 'from-submodule'
    expect(super_jobs.first[:name]).to eq 'Job from submodule commit B'
    Timeout.timeout(10) { sleep 0.5 until pending.empty? }

    visit "/projects/#{super_project}"
    expect(page).to have_content 'Branches'
    expect(page).not_to have_content 'Unresolved Branch Updates'
  end

  scenario 'a pending entry is dropped when the commit exceeds the branch trigger max age' do
    fetch_project!(sub_project)
    fetch_project!(super_project)
    Timeout.timeout(30) { sleep 1 until pending.any? }

    database[:repositories].where(id: super_project).update(branch_trigger_max_commit_age: '1 second')
    database[:commits].where(id: @super_commit).update(committer_date: Time.now - 3600)

    Timeout.timeout(60) { sleep 1 until pending.empty? }
    expect(super_jobs.count).to eq 0
  end
end
