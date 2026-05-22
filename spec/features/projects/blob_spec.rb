require 'spec_helper'

feature 'Blob view' do

  DEMO_PROJECT_ID = 'cider-ci-demo-project'
  # HEAD commit of the demo bare repo in data/repositories/cider-ci-demo-project
  DEMO_HEAD_COMMIT = 'eb15b2b3a521854ef2cb2cd8134fd3675f5053ec'

  before :each do
    @admin = FactoryBot.create(:admin)
    set_session_cookie @admin

    database[:repositories].insert(
      id:      DEMO_PROJECT_ID,
      name:    'Demo Project',
      git_url: 'local'
    )
  end

  scenario 'shows file content from a commit' do
    visit "/projects/#{DEMO_PROJECT_ID}/commits/#{DEMO_HEAD_COMMIT}/blob/cider-ci.yml"
    expect(page).to have_content 'separating jobs'
  end

  scenario '404 for a file that does not exist' do
    visit "/projects/#{DEMO_PROJECT_ID}/commits/#{DEMO_HEAD_COMMIT}/blob/no-such-file.txt"
    expect(page).to have_content 'Request ERROR 404'
  end

end
