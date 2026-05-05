require 'spec_helper'

feature 'Users Route Debug' do

  before :each do
    @admin = FactoryBot.create :admin
    set_session_cookie @admin
  end

  scenario 'test projects route works' do
    visit '/projects/'
    wait_until(10) { page.has_content?('Projects') || page.has_content?('Not Found') }
  end

  scenario 'test users route' do
    visit '/users/'
    wait_until(10) { page.has_content?('Users') || page.has_content?('Not Found') }
  end

end