require 'spec_helper'

feature 'Sign-in and sign-out'  do

  before :each do
    @admin = FactoryBot.create :admin
    @user = FactoryBot.create :user
  end


  context "no sessions exist" do

    before :each do
      database[:sessions].delete
    end

    scenario 'sign-in with login' do
      visit '/'
      fill_in 'login', with: @user.login
      click_on 'Sign in'
      expect(page).to have_content 'Sign-in'
      fill_in 'password', with: @user.password
      click_on 'Submit'
      expect(current_path).to eq '/'
      expect(page).to have_content @user.login
      expect(database[:sessions].where(user_id: @user.id).first).to be
      click_on @user.login
      click_on 'Sign out'
      expect(page).not_to have_content @user.login
      expect(database[:sessions].where(user_id: @user.id).first).not_to be
    end

    scenario 'sign-in with non primary email' do
      visit '/'
      fill_in 'login', with: database[:email_addresses].where(user_id: @user.id, is_primary: false).all.sample[:email_address]
      click_on 'Sign in'
      expect(page).to have_content 'Sign-in'
      fill_in 'password', with: @user.password
      click_on 'Submit'
      expect(current_path).to eq '/'
      expect(page).to have_content @user.login
      expect(database[:sessions].where(user_id: @user.id).first).to be
      click_on @user.login
      click_on 'Sign out'
      expect(page).not_to have_content @user.login
      expect(database[:sessions].where(user_id: @user.id).first).not_to be
    end

  end

  context "factory session" do

    scenario "using the factory session to be signed in" do
      set_session_cookie @user
      visit '/'
      expect(page).to have_content @user.login

      # switching quickly to an other user
      set_session_cookie @admin
      visit current_url
      expect(page).to have_content @admin.login

    end
  end

  context "protected pages require authentication" do

    before :each do
      database[:sessions].delete
    end

    scenario 'direct visit to a protected page redirects to sign-in and returns after login' do
      visit '/commits/'

      # redirected to the sign-in page, remembering the requested URL
      expect(page).to have_content 'Sign-in'
      expect(current_path).to eq '/sign-in'
      expect(page.current_url).to include('return-to')

      fill_in 'login',    with: @user.login
      fill_in 'password', with: @user.password
      click_on 'Submit'

      # returned to the originally requested page, now signed in
      expect(current_path).to eq '/commits/'
      expect(page).to have_content @user.login
    end

    scenario 'in-app navigation to a protected page redirects an unauthenticated user to sign-in' do
      visit '/'
      expect(page).to have_content 'Cider-CI'

      click_link 'Commits'

      expect(page).to have_content 'Sign-in'
      expect(current_path).to eq '/sign-in'
      expect(page.current_url).to include('return-to')
    end

    scenario 'the main route stays readable without authentication' do
      visit '/'
      expect(current_path).to eq '/'
      expect(page).not_to have_content 'Sign-in'
    end
  end
end
