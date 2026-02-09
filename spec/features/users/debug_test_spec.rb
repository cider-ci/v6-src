require 'spec_helper'

feature 'Users Route Debug' do

  before :each do
    @admin = FactoryBot.create :admin
    set_session_cookie @admin
  end

  scenario 'test projects route works' do
    visit '/projects/'
    wait_until(10) { page.has_content?('Projects') || page.has_content?('Not Found') }
    puts "Projects page content: #{page.has_content?('Projects') ? 'HAS Projects' : 'NO Projects'}"
  end

  scenario 'test users route' do
    visit '/users/'
    wait_until(10) { page.has_content?('Users') || page.has_content?('Not Found') }
    puts "Users page content: #{page.has_content?('Users') ? 'HAS Users' : 'NO Users'}"
    puts "Page title: #{find('h1').text rescue 'No h1 found'}"
    puts "Current path: #{current_path}"
  end

end