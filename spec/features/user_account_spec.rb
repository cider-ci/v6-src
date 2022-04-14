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

    scenario 'resets the password' do
      click_on @user.login
      click_on "My account"
      binding.pry
    end
  end
end
