const BASE_URL = import.meta.env.VITE_API_URL

export async function login(email, password){
    const response = await fetch(`${BASE_URL}/login`, {
        method: 'POST',

        headers: {
            'Content-Type' : 'application/json'
        },

        body: JSON.stringify({
            email,
            password
        })
    })

    if(!response.ok){
        throw new Error('Login Failed')
    }

    return response.json()
}