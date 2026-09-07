require 'spec_helper'

feature 'User CRUD' do

  context 'As an admin user' do
    include_context :signed_in_as_an_admin

    scenario 'can create a user and view the detail page' do
      visit '/users/'
      wait_until(10) { page.has_css?('h2', text: 'Users') }

      click_on 'New User'
      expect(page).to have_current_path('/users/new', ignore_query: true)

      fill_in 'login', with: 'testuser1'
      fill_in 'name', with: 'Test User One'
      fill_in 'password', with: 'secret123'
      click_on 'Create User'

      wait_until(10) { page.has_css?('h2', text: 'testuser1') }
      expect(page).to have_css('dd', text: 'testuser1')
      expect(page).to have_css('dd', text: 'Test User One')
    end

    scenario 'can edit a user' do
      user = FactoryBot.create(:user, login: 'editme', name: 'Before Edit')
      visit "/users/#{user[:id]}"
      wait_until(10) { page.has_css?('h2', text: 'editme') }

      click_on 'Edit'
      expect(page).to have_current_path("/users/#{user[:id]}/edit", ignore_query: true)

      fill_in 'name', with: 'After Edit'
      click_on 'Save'

      wait_until(10) { page.has_css?('dd', text: 'After Edit') }
      expect(page).to have_css('dd', text: 'editme')
    end

    scenario 'user list shows login and links to detail' do
      user = FactoryBot.create(:user, login: 'listuser', name: 'List User')
      visit '/users/'
      wait_until(10) { page.has_css?('td.login', text: 'listuser') }

      find('tr.user', text: 'listuser').click
      wait_until(10) { page.has_css?('h2', text: 'listuser') }
      expect(current_path).to eq("/users/#{user[:id]}")
    end
  end
end
