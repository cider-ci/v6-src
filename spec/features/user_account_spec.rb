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

    scenario 'a user can not set the passwort of some other user' do
      @other_user = FactoryBot.create :user
      database[:passwords].where(user_id: @other_user.id).delete()
      expect(database[:passwords].where(user_id: @other_user.id).first).not_to be
      visit "/users/#{@other_user.id}/password"
      fill_in 'password', with: 'New Secret'
      click_on 'Submit'
      expect(page).to have_content 'Request ERROR'
      expect(database[:passwords].where(user_id: @other_userid.id).first).not_to be
    end

  end

  context "an admin" do
    before :each do
      set_session_cookie @admin
      visit '/'
    end
    scenario 'resets the password of an other user' do
      database[:passwords].where(user_id: @user.id).delete()
      expect(database[:passwords].where(user_id: @user.id).first).not_to be
      visit "/users/#{@user.id}/password"
      fill_in 'password', with: 'New Secret'
      click_on 'Submit'
      expect(page).to have_content 'Request SUCCESS'
      expect(database[:passwords].where(user_id: @user.id).first).to be
    end
  end
end
