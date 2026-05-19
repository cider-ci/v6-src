require 'spec_helper'

feature 'Admin global GPG keys' do

  before :each do
    @admin = FactoryBot.create(:admin, :with_gpg_key)
    @user  = FactoryBot.create(:user,  :with_gpg_key)
    @admin_ascii_key = GPG_USER.ascii_public_key(@admin)
  end

  context 'as an admin' do
    before :each do
      set_session_cookie @admin
      visit '/admin/gpg-keys/'
    end

    scenario 'page loads showing empty state' do
      expect(page).to have_content 'No global GPG keys configured'
    end

    scenario 'can add a global GPG key' do
      fill_in 'Name', with: 'Release signing key'
      fill_in 'ascii_key', with: @admin_ascii_key
      click_on 'Add key'

      expect(page).to have_content 'Request SUCCESS'
      expect(page).to have_content 'Release signing key'

      row = database[:gpg_keys].where(user_id: nil).first
      expect(row).to be
      expect(row[:name]).to eq 'Release signing key'
      expect(row[:fingerprint]).to be_a String
      expect(row[:fingerprint]).not_to be_empty
    end

    scenario 'can add then remove a global GPG key' do
      fill_in 'Name', with: 'Temporary global key'
      fill_in 'ascii_key', with: @admin_ascii_key
      click_on 'Add key'
      expect(page).to have_content 'Request SUCCESS'
      expect(database[:gpg_keys].where(user_id: nil).count).to eq 1

      click_on 'Remove'
      expect(page).to have_content 'Request SUCCESS'
      expect(page).to have_content 'No global GPG keys configured'
      expect(database[:gpg_keys].where(user_id: nil).count).to eq 0
    end

    scenario 'global key is separate from per-user keys' do
      fill_in 'Name', with: 'Global key'
      fill_in 'ascii_key', with: @admin_ascii_key
      click_on 'Add key'
      expect(page).to have_content 'Request SUCCESS'

      expect(database[:gpg_keys].where(user_id: nil).count).to eq 1
      expect(database[:gpg_keys].where(user_id: @admin.id).count).to eq 0
    end
  end

  context 'as a non-admin user' do
    before :each do
      set_session_cookie @user
      visit '/admin/gpg-keys/'
    end

    scenario 'GET is rejected with 403' do
      expect(page).to have_content 'Request ERROR 403'
    end

    scenario 'POST is rejected with 403' do
      user_ascii_key = GPG_USER.ascii_public_key(@user)
      # dismiss the GET 403 modal first so the form is clickable
      click_on 'Dismiss'
      fill_in 'Name', with: 'Attempt'
      fill_in 'ascii_key', with: user_ascii_key
      click_on 'Add key'
      expect(page).to have_content 'Request ERROR 403'
      expect(database[:gpg_keys].where(user_id: nil).count).to eq 0
    end
  end

end
