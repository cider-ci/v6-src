require 'spec_helper'
require 'timeout'
require 'securerandom'
require 'tmpdir'

# Can the whole submodule tree of a commit be resolved through the configured
# projects? Shown per branch on the project page (check / warning mark) and in
# detail on the commit page, with a manual "Check now". Computed on branch
# updates and re-checked when a repository fetch brings new commits (a
# submodule pushed after its superproject).
feature 'Submodule resolution of commits' do

  let(:suffix)        { SecureRandom.hex(4) }
  let(:sub_project)   { "smr-sub-#{suffix}" }
  let(:super_project) { "smr-super-#{suffix}" }

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

    @root = Dir.mktmpdir('cider-ci-smr')
    sub_work      = File.join(@root, 'sub')
    @sub_remote   = File.join(@root, 'sub-remote.git')
    super_work    = File.join(@root, 'super')
    @super_remote = File.join(@root, 'super-remote.git')

    Dir.mkdir(sub_work)
    git(sub_work, 'init', '-q', '-b', 'master')
    File.write(File.join(sub_work, 'README'), 'A')
    git(sub_work, 'add', '.')
    git(sub_work, 'commit', '-q', '-m', 'A')
    git(sub_work, 'clone', '-q', '--bare', sub_work, @sub_remote)
    File.write(File.join(sub_work, 'README'), 'B')
    git(sub_work, 'commit', '-q', '-am', 'B')             # B is not pushed (yet)
    @sub_commit_b = git(sub_work, 'rev-parse', 'HEAD').strip

    Dir.mkdir(super_work)
    git(super_work, 'init', '-q', '-b', 'master')
    git(super_work, 'submodule', 'add', '-q', "file://#{@sub_remote}", 'sub')
    git(File.join(super_work, 'sub'), 'fetch', '-q', sub_work, 'master')
    git(File.join(super_work, 'sub'), 'checkout', '-q', @sub_commit_b)
    File.write(File.join(super_work, 'cider-ci.yml'), "jobs: {}\n")
    git(super_work, 'add', '.')
    git(super_work, 'commit', '-q', '-m', 'super -> sub@B')
    git(super_work, 'clone', '-q', '--bare', super_work, @super_remote)
    @super_commit = git(super_work, 'rev-parse', 'HEAD').strip

    database[:repositories].insert(id: sub_project, name: 'SMR Sub', git_url: "file://#{@sub_remote}",
                                   branch_trigger_include_match: '^__none__$')
    database[:repositories].insert(id: super_project, name: 'SMR Super', git_url: "file://#{@super_remote}",
                                   branch_trigger_include_match: '^__none__$')
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

  def resolution
    database[:commit_submodule_resolutions].where(repository_id: super_project, commit_id: @super_commit).first
  end

  scenario 'warns while the submodule commit is missing, resolves after it is pushed and fetched; Check now' do
    fetch_project!(sub_project)
    fetch_project!(super_project)

    Timeout.timeout(30) { sleep 0.5 until resolution }
    expect(resolution[:state]).to eq 'unresolved'
    expect(resolution[:unresolved]).to eq 1

    # project page: warning mark on the branch row
    visit "/projects/#{super_project}"
    within('table.branches') do
      expect(page).to have_css('tr', text: 'master')
      expect(find('tr', text: 'master')).to have_css('td.submodules a.text-warning')
    end

    # commit page: detail and "Check now"
    visit "/projects/#{super_project}/commits/#{@super_commit}"
    expect(page).to have_content '1 of 1 not resolvable'
    within('table.submodules-table') do
      expect(page).to have_content 'sub'
      expect(page).to have_content 'not resolvable'
      expect(page).to have_content sub_project   # candidate project named
    end
    checked_before = resolution[:checked_at]
    click_button 'Check now'
    Timeout.timeout(10) { sleep 0.2 until resolution[:checked_at] > checked_before }
    expect(resolution[:state]).to eq 'unresolved'

    # now push B and fetch the submodule's project: the recheck resolves it
    git(File.join(@root, 'sub'), 'push', '-q', @sub_remote, 'master')
    fetch_project!(sub_project)
    Timeout.timeout(60) { sleep 1 until resolution[:state] == 'resolved' }

    visit "/projects/#{super_project}/commits/#{@super_commit}"
    expect(page).to have_content 'all 1 resolvable'
    within('table.submodules-table') do
      expect(page).to have_link(sub_project)
    end
    visit "/projects/#{super_project}"
    expect(find('table.branches tr', text: 'master')).to have_css('td.submodules a.text-success')
  end

  scenario 'a commit without submodules shows none' do
    fetch_project!(sub_project)
    sub_commit_a = git(@sub_remote, 'rev-parse', 'HEAD').strip
    visit "/projects/#{sub_project}/commits/#{sub_commit_a}"
    expect(page).to have_css('.submodules .badge', text: 'none')
    visit "/projects/#{sub_project}"
    expect(find('table.branches tr', text: 'master')).to have_css('td.submodules', text: '—')
  end
end
