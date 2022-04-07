require 'spec_helper'


feature 'Initial Setup'  do

  context 'creating the initial admin' do

    scenario 'with email' do
      visit '/'
      expect(page).to have_content 'Initial Setup'
      fill_in 'login', with: 'admin@localhost'
      fill_in 'password', with: 'secret'
      click_on 'Submit'
      expect(page).to have_content 'Sign-in'
      fill_in 'password', with: 'secret'
      click_on 'Submit'
      expect(page).to have_content 'admin@localhost'
      click_on 'admin@localhost'
      click_on 'Sign out'
      expect(page).not_to have_content 'admin@localhost'
    end


    scenario 'with login' do
      visit '/'
      expect(page).to have_content 'Initial Setup'
      fill_in 'login', with: 'admin'
      fill_in 'password', with: 'secret'
      click_on 'Submit'
      expect(page).to have_content 'Sign-in'
      fill_in 'password', with: 'secret'
      click_on 'Submit'
      expect(page).to have_content 'admin'
      # the only admin has the login 'admin'
      expect(database[:users].first[:login]).to eq 'admin'
      # there is a session
      expect(database[:sessions].first).to be
      # there is a password
      expect(database[:passwords].first).to be
      click_on 'admin'
      click_on 'Sign out'
      expect(page).not_to have_content 'admin'
      # there is no (more) session
      expect(database[:sessions].first).to be_nil
    end

    context 'with and existing admin' do

      before :each do
        @admin = FactoryBot.create :admin
      end

      scenario '(re-)initialization fails' do
        visit '/init'
        fill_in 'login', with: 'admin'
        fill_in 'password', with: 'secret'
        click_on 'Submit'
        expect(page).to have_content 'failed'
        expect(page).to have_content 'Expected no existing admin'
      end
    end
  end
end
