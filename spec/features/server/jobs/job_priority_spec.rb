require 'spec_helper'
require 'timeout'
require 'securerandom'
require 'tmpdir'

# Job priority: default 0, set via `priority:` in the job's cider-ci
# configuration (auto-triggered and UI-triggered jobs alike), overridable at
# run time on the job page.
feature 'Job priority' do

  let(:project_id) { "job-priority-#{SecureRandom.hex(4)}" }

  CIDER_CI_YML = <<~YML
    jobs:
      prio-auto:
        name: Auto-triggered with priority
        priority: 7
        run_when:
          any branch:
            type: branch
            include_match: ^.*$
        task: 'true'
      prio-manual:
        name: Manually triggered with priority
        priority: -3
        task: 'true'
      prio-default:
        name: Without priority
        task: 'true'
  YML

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    @repo_dir = Dir.mktmpdir('cider-ci-prio-repo')
    File.write(File.join(@repo_dir, 'cider-ci.yml'), CIDER_CI_YML)
    git = ->(*args) { system('git', '-C', @repo_dir, *args, out: File::NULL, err: File::NULL) or raise "git #{args.join(' ')} failed" }
    git.call('init', '-q', '-b', 'master')
    git.call('-c', 'user.name=spec', '-c', 'user.email=spec@example.com', '-c', 'commit.gpgsign=false',
             'add', 'cider-ci.yml')
    git.call('-c', 'user.name=spec', '-c', 'user.email=spec@example.com', '-c', 'commit.gpgsign=false',
             'commit', '-q', '-m', 'priority fixture')
    @commit_id = `git -C #{@repo_dir} rev-parse HEAD`.strip

    database[:repositories].insert(id: project_id, name: 'Priority Fixture', git_url: "file://#{@repo_dir}",
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

  scenario 'auto-triggered and manually triggered jobs take the configured priority; default is 0' do
    Timeout.timeout(30) { sleep 1 until job_row('prio-auto') }
    expect(job_row('prio-auto')[:priority]).to eq 7

    visit "/projects/#{project_id}/commits/#{@commit_id}/jobs"
    find('tr', text: 'Manually triggered with priority').find('button', text: 'Run').click
    Timeout.timeout(20) { sleep 0.5 until job_row('prio-manual') }
    find('tr', text: 'Without priority').find('button', text: 'Run').click
    Timeout.timeout(20) { sleep 0.5 until job_row('prio-default') }

    expect(job_row('prio-manual')[:priority]).to eq(-3)
    expect(job_row('prio-default')[:priority]).to eq 0
    # UI-triggered jobs record the user; auto-triggered ones do not
    expect(job_row('prio-manual')[:created_by]).to eq @admin.id
    expect(job_row('prio-auto')[:created_by]).to be_nil

    # the recorded jobs table shows the priority
    recorded = find('h4', text: 'Recorded Jobs').find(:xpath, 'following-sibling::table[1]')
    within(recorded) do
      expect(page).to have_css('th', text: 'Priority')
      expect(find('tr', text: 'prio-manual')).to have_content('-3')
      expect(find('tr', text: 'prio-auto')).to have_content('7')
    end
  end

  scenario 'the priority can be overridden on the job page' do
    Timeout.timeout(30) { sleep 1 until job_row('prio-auto') }
    visit "/projects/#{project_id}/commits/#{@commit_id}/jobs/#{job_row('prio-auto')[:id]}"
    expect(page).to have_field('Priority', with: '7')

    fill_in 'Priority', with: '42'
    click_button 'Set priority'

    expect(page).to have_field('Priority', with: '42')
    Timeout.timeout(10) { sleep 0.2 until job_row('prio-auto')[:priority] == 42 }
    expect(job_row('prio-auto')[:priority]).to eq 42
  end
end
