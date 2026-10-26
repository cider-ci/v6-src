require 'spec_helper'
require 'securerandom'
require 'digest'

feature 'Executor load overview' do

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    @executor_id = SecureRandom.uuid
    token = SecureRandom.hex(32)
    database[:executors].insert(
      id: @executor_id, name: 'load-box', max_load: 4.0, enabled: true,
      token_hash: Digest::SHA256.hexdigest(token), token_part: token[0, 8])

    @project_id = 'load-project'
    database[:repositories].insert(id: @project_id, name: 'Load Project',
                                   git_url: "local://#{@project_id}")
    @commit_id = 'a' * 40
    database[:commits].insert(id: @commit_id, tree_id: 'b' * 40, subject: 'load',
                              author_name: 'A', committer_name: 'A')
    @job_id = SecureRandom.uuid
    database[:jobs].insert(id: @job_id, project_id: @project_id, commit_id: @commit_id,
                           key: 'tests', name: 'Load Tests', state: 'executing')
  end

  def insert_trial!(task_name, load, state)
    task_id = SecureRandom.uuid
    database[:tasks].insert(id: task_id, job_id: @job_id, name: task_name,
                            state: 'executing', load: load)
    trial_id = SecureRandom.uuid
    database[:trials].insert(id: trial_id, task_id: task_id, state: state,
                             executor_id: @executor_id,
                             dispatched_at: Time.now.utc, started_at: Time.now.utc)
    trial_id
  end

  scenario 'the list shows current load and trial count per executor and in total' do
    insert_trial!('spec a', 1.5, 'executing')
    insert_trial!('spec b', 1.0, 'dispatching')
    insert_trial!('done', 1.0, 'passed') # finished trials do not count

    visit '/executors/'
    within 'tr', text: 'load-box' do
      expect(page).to have_css '.load-text', text: '2.5 / 4'
      expect(page).to have_css 'td.trials-count', text: '2'
    end
    expect(page).to have_css '.load-summary', text: '2 running trials'
    expect(page).to have_css '.load-summary .load-text', text: '2.5 / 4'
  end

  scenario 'the detail page lists the trials the executor works on and reloads in the background' do
    trial_id = insert_trial!('spec a', 1.5, 'executing')
    insert_trial!('done', 1.0, 'passed')

    visit "/executors/#{@executor_id}"
    page.execute_script 'window.__no_page_reload = true'

    within 'table.current-trials' do
      expect(page).to have_link trial_id[0, 8], href: "/trials/#{trial_id}"
      expect(page).to have_link 'spec a'
      expect(page).to have_link 'Load Tests'
      expect(page).not_to have_text 'done'
    end
    expect(page).to have_css '.load-text', text: '1.5 / 4'

    # new work shows up, finished work disappears, without reloading the page
    insert_trial!('spec c', 1.0, 'executing')
    database[:trials].where(id: trial_id).update(state: 'passed')
    within 'table.current-trials' do
      expect(page).to have_link 'spec c', wait: 15
      expect(page).not_to have_link 'spec a'
    end
    expect(page).to have_css '.load-text', text: '1 / 4'

    database[:trials].where(executor_id: @executor_id).update(state: 'passed')
    expect(page).to have_css '.current-trials-empty', wait: 15

    expect(page.evaluate_script('window.__no_page_reload')).to be true
  end


  scenario 'the detail page shows the accepted repositories reported by the executor' do
    visit "/executors/#{@executor_id}"
    expect(page).to have_css '.accepted-repositories', text: 'accepts all repositories'

    database.run(<<~SQL)
      UPDATE executors SET accepted_repositories =
        '[{"url": "^https://github\\\\.com/leihs/.+$"},
          {"url": "https://github.com/Madek/Madek.git", "branches": ["^master$"]}]'
      WHERE id = '#{@executor_id}'
    SQL
    visit "/executors/#{@executor_id}"
    within '.accepted-repositories table' do
      within 'tr', text: 'leihs' do
        expect(page).to have_css 'code', text: '^https://github\\.com/leihs/.+$'
        expect(page).to have_css 'td.branches', text: 'any branch'
      end
      within 'tr', text: 'Madek' do
        expect(page).to have_css 'td.branches', text: 'head of'
        expect(page).to have_css 'td.branches code', text: '^master$'
      end
    end
  end
end
