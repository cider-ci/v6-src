require 'spec_helper'

feature 'Jobs' do

  JOBS_PROJECT_ID  = 'cider-ci-demo-project'
  JOBS_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:      JOBS_PROJECT_ID,
      name:    'Demo Project',
      git_url: 'local'
    )
  end

  scenario 'lists available jobs from cider-ci.yml' do
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs"
    expect(page).to have_content 'Available Jobs'
    expect(page).to have_content 'introduction-demo'
  end

  scenario 'records a job when Run is clicked' do
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs"
    expect(page).to have_content 'introduction-demo'

    first(:button, 'Run').click
    expect(page).to have_content 'Recorded Jobs'
    expect(page).to have_css '.badge', text: 'pending'
  end

  scenario 'shows no recorded jobs initially' do
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs"
    expect(page).not_to have_content 'Recorded Jobs'
  end

  scenario 'job detail page shows tasks after triggering a job' do
    visit "/projects/#{JOBS_PROJECT_ID}/commits/#{JOBS_HEAD_COMMIT}/jobs"
    find('tr', text: 'Introduction Demo').find('button', text: 'Run').click
    expect(page).to have_content 'Recorded Jobs'

    find('a code', text: 'introduction-demo').click

    expect(page).to have_content 'Tasks'
    expect(page).to have_css '.badge', text: 'pending'
    expect(page).to have_content 'main'
  end

end
