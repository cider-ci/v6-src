require 'spec_helper'
require 'securerandom'

# When did it run? Deploy jobs are judged by their last run, not by their
# creation: the job listing and the job page show when a job finished, the
# task and trial pages when each trial was created, dispatched, started and
# finished.
feature 'Job and trial timestamps' do

  let(:project_id) { 'timestamps-test-project' }
  let(:commit_id)  { 'c' * 40 }

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin
    database[:repositories].insert(id: project_id, name: 'Timestamps Test', git_url: 'local')
    database[:commits].insert(id: commit_id, tree_id: 'd' * 40, subject: 'Timestamps',
                              author_name: 'A', committer_name: 'A', committer_date: Time.now.utc)
    @created  = Time.now.utc - 3 * 3600
    @finished = Time.now.utc - 2 * 3600
    @job_id = SecureRandom.uuid
    database[:jobs].insert(id: @job_id, project_id: project_id, commit_id: commit_id,
                           key: 'deploy', name: 'Deploy Job', state: 'passed',
                           created_at: @created, finished_at: @finished)
    @task_id = SecureRandom.uuid
    database.fetch("INSERT INTO tasks (id, job_id, name, state, traits, load, spec)
                    VALUES (?, ?, ?, ?, '{}', 1.0, '{}'::jsonb)", @task_id, @job_id, 'deploy', 'passed').first
    @trial_id = SecureRandom.uuid
    database[:trials].insert(id: @trial_id, task_id: @task_id, state: 'passed',
                             created_at: @created, dispatched_at: @created + 60,
                             started_at: @created + 70, finished_at: @finished)
  end

  scenario 'the job listing shows when a job finished' do
    visit '/jobs/'
    expect(page).to have_css('tr', text: 'Deploy Job', wait: 10)
    row = find('tr', text: 'Deploy Job')
    expect(row).to have_content 'about 3 hours ago'   # created
    expect(row).to have_content 'about 2 hours ago'   # finished
  end

  scenario 'the job page shows created and finished, the task page the trials with their times' do
    visit "/projects/#{project_id}/commits/#{commit_id}/jobs/#{@job_id}"
    within('.job-times') do
      expect(page).to have_content 'created about 3 hours ago'
      expect(page).to have_content 'finished about 2 hours ago'
    end

    visit "/projects/#{project_id}/commits/#{commit_id}/jobs/#{@job_id}/tasks/#{@task_id}"
    within('table.trials') do
      expect(page).to have_content 'passed'
      expect(page).to have_content 'about 3 hours ago'
      expect(page).to have_content 'about 2 hours ago'
      expect(page).to have_content '58m 50s'   # started -> finished
    end
  end

  scenario 'the trial page shows the timeline' do
    visit "/trials/#{@trial_id}"
    within('.trial-times') do
      expect(page).to have_content 'created about 3 hours ago'
      expect(page).to have_content 'dispatched about 3 hours ago'
      expect(page).to have_content 'finished about 2 hours ago'
      expect(page).to have_content 'waited 1m 10s'
      expect(page).to have_content 'ran 58m 50s'
    end
  end

end
