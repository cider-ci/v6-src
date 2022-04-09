require 'spec_helper'

feature 'User Account'  do

  before :each do
    @admin = FactoryBot.create :admin
    @user = FactoryBot.create :user
  end


  scenario 'using the navbar "My account" link' do
    set_session_cookie @user
    visit '/'
    binding.pry
  end


end


