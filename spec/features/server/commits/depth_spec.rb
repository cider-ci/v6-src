require 'spec_helper'
require 'timeout'
require 'securerandom'
require 'tmpdir'

# commits.depth: the generation number, 0 for a root, else 1 + the largest
# parent depth. The commits page shows head.depth - commit.depth as "n
# commits behind". Depths of already imported commits must survive later
# imports (they used to be reset to 0), and a merge commit's depth must not
# depend on which of its branches was imported first.
feature 'Commit depths' do

  let(:project_id) { "depth-#{SecureRandom.hex(4)}" }

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

  def depth(id) = database[:commits].where(id: id).get(:depth)

  # push, fetch, and wait until the head has its depth (set at the end of the import)
  def fetch!(head)
    git(@work, 'push', '-q', '--all', @remote)
    visit "/projects/#{project_id}"
    find('button', text: /[Ff]etch/).click
    Timeout.timeout(30) { sleep 0.5 while depth(head).nil? }
  rescue Timeout::Error
    visit "/projects/#{project_id}"
    raise "no depth for #{head} after 30s; project page:\n#{find('dl').text}"
  end

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    @root   = Dir.mktmpdir('cider-ci-depth')
    @work   = File.join(@root, 'work')
    @remote = File.join(@root, 'remote.git')
    Dir.mkdir(@work)
    git(@work, 'init', '-q', '-b', 'master')
    # master: c0 - c1 - c2 - m   feature: c1 - f1 - f2 (merged into m)
    @c0 = commit!('master zero')
    @c1 = commit!('master one')
    git(@work, 'checkout', '-q', '-b', 'feature')
    @f1 = commit!('feature one')
    @f2 = commit!('feature two')
    git(@work, 'checkout', '-q', 'master')
    @c2 = commit!('master two')
    git(@work, 'merge', '-q', '--no-ff', '--no-edit', 'feature')
    @m = git(@work, 'rev-parse', 'HEAD').strip
    git(@work, 'init', '-q', '--bare', @remote)
    database[:repositories].insert(id: project_id, name: 'Depth', git_url: "file://#{@remote}",
                                   branch_trigger_include_match: '^__none__$')
    reload_server_repository_state
    sleep 1
  end

  after :each do
    FileUtils.rm_rf(@root) if @root
  end

  scenario 'the import computes depths and later imports keep them' do
    fetch!(@m)
    expect([@c0, @c1, @c2, @f1, @f2].map { |c| depth(c) }).to eq [0, 1, 2, 2, 3]
    expect(depth(@m)).to eq 4   # 1 + max(master two: 2, feature two: 3)

    # a new commit on master: the existing depths stay, the new one is 5
    c3 = commit!('master three')
    fetch!(c3)
    expect(depth(c3)).to eq 5
    expect([@c0, @c1, @c2, @f1, @f2, @m].map { |c| depth(c) }).to eq [0, 1, 2, 2, 3, 4]

    # the commits page: the merge is 1 behind master's head, feature two 2
    visit "/commits/?heads=false&project=#{project_id}"
    expect(page).to have_text 'master three'
    expect(find('tr', text: "Merge branch 'feature'")).to have_css('sub', text: '1')
    expect(find('tr', text: 'feature two')).to have_css('sub', text: '2')

    # the full recompute (migration 37) yields the same depths
    database.run('UPDATE commits SET depth = NULL')
    expect(database.fetch('SELECT recompute_commit_depths() AS d').first[:d]).to eq 5
    expect([@c0, @c1, @c2, @f1, @f2, @m, c3].map { |c| depth(c) }).to eq [0, 1, 2, 2, 3, 4, 5]
  end

end
