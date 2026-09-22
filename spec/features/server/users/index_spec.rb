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
      
      # Wait for the page to load
      wait_until(10) do
        page.has_content?('Users') 
      end
      
      # For now, just verify the route resolves to something
      expect(page).to have_current_path('/users/', ignore_query: true)
      
    end

  end

  context 'As a regular user' do
    before :each do
      @current_user = @user
    end
    include_context :signed_in_as_current_user

    scenario 'cannot access users index page' do
      visit '/users/'
      wait_until(10) do
        page.has_content?('Authorization not satisfied') 
      end
    end
  end

  context 'As an unauthenticated user' do
    scenario 'is redirected to the sign-in page' do
      visit '/users/'
      wait_until(10) do
        page.has_content?('Sign-in')
      end
      expect(page).to have_current_path('/sign-in', ignore_query: true)
      expect(page.current_url).to include('return-to')
    end
  end
end