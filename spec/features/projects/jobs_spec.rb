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

end
