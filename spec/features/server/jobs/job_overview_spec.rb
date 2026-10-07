require 'spec_helper'
require 'securerandom'

# Job page overview (legacy parity): a stacked progress bar over the tasks
# (passed | failed | retrying | executing | pending) and a task list filtered
# by "Unpassed" (default), "With unpassed trials" or "All".
feature 'Job page overview' do

  let(:project_id) { 'overview-test-project' }
  let(:commit_id)  { 'a' * 40 }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: project_id, name: 'Overview Test', git_url: 'local')
    database[:commits].insert(id: commit_id, tree_id: 'b' * 40, subject: 'Overview',
                              author_name: 'A', committer_name: 'A', committer_date: Time.now.utc)
    @job_id = SecureRandom.uuid
    database[:jobs].insert(id: @job_id, project_id: project_id, commit_id: commit_id,
                           key: 'overview', name: 'Overview Job', state: 'executing')
  end

  def seed_task(name, state, trial_states)
    task_id = SecureRandom.uuid
    database.fetch("INSERT INTO tasks (id, job_id, name, state, traits, load, spec)
                    VALUES (?, ?, ?, ?, '{}', 1.0, '{}'::jsonb)", task_id, @job_id, name, state).first
    trial_states.each { |s| database[:trials].insert(id: SecureRandom.uuid, task_id: task_id, state: s) }
  end

  def task_names
    all('table.tasks tbody tr td:first-child code').map(&:text)
  end

  scenario 'progress bar sections and the task filters' do
    seed_task('t1 passed after retry', 'passed',    %w[failed passed])
    seed_task('t2 passed',             'passed',    %w[passed])
    seed_task('t3 failed',             'failed',    %w[failed failed])
    seed_task('t4 retrying',           'executing', %w[failed executing])
    seed_task('t5 executing',          'executing', %w[executing])
    seed_task('t6 pending',            'pending',   %w[pending])

    visit "/projects/#{project_id}/commits/#{commit_id}/jobs/#{@job_id}"
    expect(page).to have_content 'Overview Job'

    within('.job-progress') do
      expect(page).to have_content '6 tasks'
      expect(page).to have_content '2 passed'
      expect(page).to have_content '1 failed'
      expect(page).to have_content '1 retrying'
      expect(page).to have_content '1 executing'
      expect(page).to have_content '1 pending'
      expect(page).to have_content '3 of 6 finished'
      widths = all('.progress-bar').map { |b| [b['data-category'], b[:style][/width:\s*([\d.]+)%/, 1].to_f.round] }
      expect(widths).to eq [['passed', 33], ['failed', 17], ['retrying', 17], ['executing', 17], ['pending', 17]]
    end

    # default: unpassed tasks only
    within('.task-filters') do
      expect(page).to have_button(text: /Unpassed\s*4/)
      expect(page).to have_button(text: /With unpassed trials\s*5/)
      expect(page).to have_button(text: /All\s*6/)
    end
    expect(task_names).to eq ['t3 failed', 't4 retrying', 't5 executing', 't6 pending']

    within('.task-filters') { click_button(text: /With unpassed trials/) }
    expect(task_names).to eq ['t1 passed after retry', 't3 failed', 't4 retrying', 't5 executing', 't6 pending']

    within('.task-filters') { click_button(text: /\AAll/) }
    expect(task_names.size).to eq 6
  end

  scenario 'an all-green job shows an empty unpassed list with a link to all' do
    seed_task('t1', 'passed', %w[passed])
    database[:jobs].where(id: @job_id).update(state: 'passed')
    visit "/projects/#{project_id}/commits/#{commit_id}/jobs/#{@job_id}"
    expect(page).to have_css('.tasks-empty', text: 'No tasks in "Unpassed"')
    within('.tasks-empty') { click_link 'show all' }
    expect(task_names).to eq ['t1']
  end
end
