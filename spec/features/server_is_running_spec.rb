require 'spec_helper'


feature 'The application server is running', feature: true do
  scenario 'and serving webpages' do
    visit '/'
    expect(page).to have_content 'Welcome to Cider-CI'
  end
end
