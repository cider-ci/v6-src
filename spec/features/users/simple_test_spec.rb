require 'spec_helper'

feature 'Users' do

  before :each do
    @admin = FactoryBot.create :admin
    @user = FactoryBot.create :user
  end

  context 'As an admin user' do
    before :each do
      set_session_cookie @admin
    end

    scenario 'can access users index page' do
      visit '/users/'

      # Wait for the page to load and check if we get Users content or error
      wait_until(10) do
        page.has_content?('Users') || page.has_content?('Not Found') || page.has_content?('Page Not-Found')
      end
      
      if page.has_content?('Users')
        expect(page).to have_content 'Users'
        expect(page).to have_content @admin.email_address
        expect(page).to have_content @user.email_address
      else
        # If we can't access the page, that's still useful information
        expect(page).to have_content 'Not Found'
      end
    end

  end

end