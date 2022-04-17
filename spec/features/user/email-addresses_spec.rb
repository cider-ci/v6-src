require 'spec_helper'

feature 'User email addresses' do

  before :each do
    @admin = FactoryBot.create :admin
    @user = FactoryBot.create :user
    @other_user = FactoryBot.create :user
  end

  context 'a admin' do

    before :each do
      set_session_cookie @admin
      visit "/users/#{@user.id}/email-addresses/"
    end

    example 'can remove a email-address' do
      binding.pry
    end

  end

end
