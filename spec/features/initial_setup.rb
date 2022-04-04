require 'spec_helper'


feature 'Initial Setup'  do
  scenario 'creating the initial admin' do
    visit '/'
    expect(page).to have_content 'Initial Setup'
    fill_in 'email', with: 'admin@localhost'
    fill_in 'password', with: 'secret'
    click_on 'Submit'
    expect(page).to have_content 'Sign-in'
    fill_in 'password', with: 'secret'
    click_on 'Submit'
    expect(page).to have_content 'admin@localhost'
    binding.pry
  end
end
