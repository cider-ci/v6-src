require 'spec_helper'

feature 'User Account'  do

  before :each do
    @admin = FactoryBot.create :admin
    @user = FactoryBot.create :user
  end


  context "a user as itself" do

    before :each do
      set_session_cookie @user
      visit '/'
    end

    scenario 'uses the navbar "My account" link' do
      click_on @user.login
      click_on "My account"
      expect(current_path).to be== "/users/#{@user.id}"
    end

    scenario 'resets the password and signs in again' do
      click_on @user.login
      click_on "My account"
      click_on "Reset password"
      fill_in 'password', with: 'New Secret'
      click_on 'Submit'
      expect(page).to have_content 'Request SUCCESS'
      click_on @user.login
      click_on 'Sign out'
      expect(page).not_to have_content @user.login
      fill_in 'login', with: @user.login
      click_on 'Sign in'
      expect(page).to have_content 'Sign-in'
      fill_in 'password', with: 'New Secret'
      click_on 'Submit'
      expect(page).to have_content @user.login
    end
  end
end
