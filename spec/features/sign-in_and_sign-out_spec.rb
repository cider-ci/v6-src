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
      fill_in 'login', with: database[:email_addresses].where(user_id: @user.id, is_primary: false).all.sample[:email]
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
end
