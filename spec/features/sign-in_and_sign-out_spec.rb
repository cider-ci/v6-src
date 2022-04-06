require 'spec_helper'

feature 'Sign-in and sign-out'  do

  before :each do
    @admin = FactoryBot.create :admin
    @user = FactoryBot.create :user
  end

  scenario 'sign-in' do
    visit '/'
    binding.pry
  end
end
