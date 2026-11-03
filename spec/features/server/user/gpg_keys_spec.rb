require 'spec_helper'

feature 'User GPG keys' do

  before :each do
    # ensure an admin exists so the SPA does not redirect to Initial Setup
    @admin = FactoryBot.create(:admin)
    @user  = FactoryBot.create(:user)
    @other = FactoryBot.create(:user)
    @ascii_key = STATIC_GPG_KEY
  end

  context 'as the user themselves' do
    before :each do
      set_session_cookie @user
      visit "/users/#{@user.id}/gpg-keys/"
    end

    scenario 'page loads showing empty state' do
      expect(page).to have_content 'No GPG keys configured'
    end

    scenario 'can add a GPG key' do
      fill_in 'Name', with: 'My signing key'
      fill_in 'ascii_key', with: @ascii_key
      click_on 'Add key'

      expect(page).to have_content 'Request SUCCESS'
      expect(page).to have_content 'My signing key'

      row = database[:gpg_keys].where(user_id: @user.id).first
      expect(row).to be
      expect(row[:name]).to eq 'My signing key'
      expect(row[:fingerprint]).to be_a String
      expect(row[:fingerprint]).not_to be_empty
    end

    scenario 'the key page shows what the key contains, the armored key can be copied' do
      fill_in 'Name', with: 'My signing key'
      fill_in 'ascii_key', with: @ascii_key
      click_on 'Add key'
      expect(page).to have_content 'Request SUCCESS'
      click_on 'My signing key'

      key = database[:gpg_keys].where(user_id: @user.id).first
      expect(page).to have_current_path("/users/#{@user.id}/gpg-keys/#{key[:id]}", ignore_query: true)
      within('.gpg-key-detail') do
        expect(page).to have_css 'h2', text: 'My signing key'
        expect(page).to have_content '247047366DDD1587BF63A6043F8629952915F2B7'
        expect(page).to have_content 'EdDSA 256 bits'
        expect(page).to have_content '2026-05-20'
        expect(page).to have_css '.validity', text: 'never expires'
        expect(page).to have_css '.user-ids', text: 'Test User <test@cider-ci.example>'
        expect(page).to have_css 'table.subkeys', text: 'CE23B55AE633FFEBF9FF999D4C83049B7EBF2BA9'
        expect(page).to have_css 'table.subkeys', text: 'encryption'
        expect(find('textarea').value.strip).to eq @ascii_key.strip
        expect(find('a.download-key')[:download]).to eq '247047366ddd1587bf63a6043f8629952915f2b7.asc'
        click_on 'Copy'
        expect(page).to have_css '.copy-key', text: 'Copied'
      end

      click_on 'Remove this key'
      expect(page).to have_current_path("/users/#{@user.id}/gpg-keys/", ignore_query: true)
      expect(page).to have_content 'No GPG keys configured'
      expect(database[:gpg_keys].where(user_id: @user.id).count).to eq 0
    end

    scenario 'can add then remove a GPG key' do
      fill_in 'Name', with: 'Temp key'
      fill_in 'ascii_key', with: @ascii_key
      click_on 'Add key'
      expect(page).to have_content 'Request SUCCESS'
      expect(database[:gpg_keys].where(user_id: @user.id).count).to eq 1

      click_on 'Remove'
      expect(page).to have_content 'Request SUCCESS'
      expect(page).to have_content 'No GPG keys configured'
      expect(database[:gpg_keys].where(user_id: @user.id).count).to eq 0
    end
  end

  context 'as a different (non-admin) user' do
    before :each do
      set_session_cookie @other
      visit "/users/#{@user.id}/gpg-keys/"
    end

    scenario 'GET is rejected with 403' do
      expect(page).to have_content 'Request ERROR 403'
    end

    scenario 'POST is rejected with 403' do
      # dismiss the GET 403 modal first so the form is clickable
      click_on 'Dismiss'
      fill_in 'Name', with: 'Attempt'
      fill_in 'ascii_key', with: @ascii_key
      click_on 'Add key'
      expect(page).to have_content 'Request ERROR 403'
      expect(database[:gpg_keys].where(user_id: @user.id).count).to eq 0
    end
  end

  context 'as an admin' do
    before :each do
      set_session_cookie @admin
      visit "/users/#{@user.id}/gpg-keys/"
    end

    scenario 'can view another user\'s GPG keys' do
      expect(page).to have_content 'No GPG keys configured'
    end
  end

end
